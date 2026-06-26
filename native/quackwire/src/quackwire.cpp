#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <exception>
#include <memory>
#include <string>

// Pulls in duckdb-quack's message-layer types. quack_message.cpp transitively
// references QuackServer::QUACK_VERSION, which is normally declared in
// quack_server.hpp; the upstream header drags in httplib.hpp. We sidestep
// that by shipping a minimal shim header at
// `native/quackwire/include/shim/quack_server.hpp`, placed BEFORE the
// upstream `src/include/` on the search path via CMakeLists.txt's
// `target_include_directories(... BEFORE PRIVATE ...)`. See design doc 9.4.
#include "quack_message.hpp"

#include "duckdb/common/allocator.hpp"
#include "duckdb/common/arrow/arrow.hpp"
#include "duckdb/common/arrow/arrow_converter.hpp"
#include "duckdb/common/serializer/memory_stream.hpp"
#include "duckdb/common/shared_ptr.hpp"
#include "duckdb/common/types/data_chunk.hpp"
#include "duckdb/common/types/hugeint.hpp"
#include "duckdb/common/types/value.hpp"
#include "duckdb/common/unordered_map.hpp"
#include "duckdb/common/vector.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_properties.hpp"
#include "duckdb/main/connection.hpp"
#include "duckdb/main/database.hpp"

#include <mutex>

// Scala 3 `object` declarations compile to a singleton class whose JVM name
// ends with `$`. JNI's symbol-name mangling escapes `$` as `_00024`, so a
// `@native def smokeAnswer()` inside `object QuackNativeBridge` is looked up
// by the JVM as the instance method below. The JNIEnv parameter therefore
// gets a `jobject` (the singleton instance), not a `jclass`. EVERY entry
// point in this file must follow that convention.
extern "C" JNIEXPORT jint JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_smokeAnswer(JNIEnv*, jobject) {
  return 42;
}

namespace {

jbyteArray to_jbyte_array(JNIEnv* env, const duckdb::MemoryStream& s) {
  auto len = static_cast<jsize>(s.GetPosition());
  jbyteArray arr = env->NewByteArray(len);
  if (arr == nullptr) {
    return nullptr;
  }
  env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(s.GetData()));
  return arr;
}

std::string jstring_to_std(JNIEnv* env, jstring js) {
  if (js == nullptr) {
    return {};
  }
  const char* raw = env->GetStringUTFChars(js, nullptr);
  if (raw == nullptr) {
    return {};
  }
  std::string out(raw);
  env->ReleaseStringUTFChars(js, raw);
  return out;
}

void throw_runtime(JNIEnv* env, const char* what) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls != nullptr) {
    env->ThrowNew(cls, what);
  }
}

// Pulls a 128-bit magnitude out of a Java BigInteger via the standard
// `byte[] toByteArray()` form (big-endian, two's-complement). The wire's
// `hugeint_t` is `{int64_t upper, uint64_t lower}`. Raises a Java
// RuntimeException via `throw_runtime` on:
//   - null BigInteger
//   - inability to resolve the `toByteArray` method (should not happen on
//     a real BigInteger, but a malicious / mis-typed caller could trigger it)
//   - a magnitude that does not fit in 128 bits (i.e. byte array longer
//     than 17 bytes - 16 magnitude + 1 optional sign byte for positive
//     values with the high bit set)
// On any of those paths the function leaves a pending Java exception and
// returns hugeint_t(0,0); callers MUST check `env->ExceptionCheck()`
// before using the returned value.
duckdb::hugeint_t jbiginteger_to_hugeint(JNIEnv* env, jobject big_integer) {
  if (big_integer == nullptr) {
    throw_runtime(env, "FetchRequest resultUuid is null");
    return duckdb::hugeint_t(0, 0);
  }
  jclass big_int_cls = env->GetObjectClass(big_integer);
  jmethodID to_byte_array = env->GetMethodID(big_int_cls, "toByteArray", "()[B");
  if (to_byte_array == nullptr) {
    // GetMethodID has already raised NoSuchMethodError; do not stack a
    // second exception, just bail.
    return duckdb::hugeint_t(0, 0);
  }
  jbyteArray bytes_j = static_cast<jbyteArray>(env->CallObjectMethod(big_integer, to_byte_array));
  if (env->ExceptionCheck() || bytes_j == nullptr) {
    if (!env->ExceptionCheck()) {
      throw_runtime(env, "BigInteger.toByteArray returned null");
    }
    return duckdb::hugeint_t(0, 0);
  }
  jsize len = env->GetArrayLength(bytes_j);
  // A 128-bit BigInteger occupies at most 17 bytes in its two's-complement
  // byte[] form: 16 magnitude bytes plus an optional leading 0x00 for a
  // positive value whose high bit is set. Anything larger - or a 17-byte
  // encoding whose leading byte is non-zero (meaning the magnitude itself
  // is >128 bits, e.g. 2^128 encodes as 0x01 followed by 16 zero bytes) -
  // is outside the wire format and we refuse it rather than silently truncate.
  if (len > 17) {
    throw_runtime(env, "FetchRequest resultUuid does not fit in 128 bits");
    return duckdb::hugeint_t(0, 0);
  }
  if (len == 17) {
    jbyte first = 0;
    env->GetByteArrayRegion(bytes_j, 0, 1, &first);
    if (first != 0) {
      throw_runtime(env, "FetchRequest resultUuid does not fit in 128 bits");
      return duckdb::hugeint_t(0, 0);
    }
  }
  jbyte buf[16] = {0};
  // toByteArray returns big-endian two's-complement. Place the trailing
  // bytes into the right end of our 16-byte buffer so a short array
  // (e.g. positive small UUID with leading-zero suppression) is
  // zero-extended. A 17-byte positive value's leading 0x00 sign byte
  // gets discarded by the `src_offset = len - 16` clamp.
  jsize copy_len = len < 16 ? len : 16;
  jsize src_offset = len - copy_len;
  env->GetByteArrayRegion(bytes_j, src_offset, copy_len, buf + (16 - copy_len));
  uint64_t upper_u = 0;
  uint64_t lower_u = 0;
  for (int i = 0; i < 8; ++i) {
    upper_u = (upper_u << 8) | static_cast<uint8_t>(buf[i]);
    lower_u = (lower_u << 8) | static_cast<uint8_t>(buf[8 + i]);
  }
  return duckdb::hugeint_t(static_cast<int64_t>(upper_u), lower_u);
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_serializeConnectionRequest(
    JNIEnv* env, jobject, jstring token_j) {
  try {
    auto token = jstring_to_std(env, token_j);
    duckdb::ConnectionRequestMessage msg(token);
    duckdb::MemoryStream stream;
    msg.ToMemoryStream(stream);
    return to_jbyte_array(env, stream);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeConnectionRequest");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_serializePrepareRequest(
    JNIEnv* env, jobject, jstring connection_id_j, jstring sql_j) {
  try {
    auto connection_id = jstring_to_std(env, connection_id_j);
    auto sql = jstring_to_std(env, sql_j);
    duckdb::PrepareRequestMessage msg(connection_id, sql);
    duckdb::MemoryStream stream;
    msg.ToMemoryStream(stream);
    return to_jbyte_array(env, stream);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializePrepareRequest");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_serializeFetchRequest(
    JNIEnv* env, jobject, jstring connection_id_j, jobject result_uuid_j) {
  try {
    auto connection_id = jstring_to_std(env, connection_id_j);
    duckdb::hugeint_t uuid = jbiginteger_to_hugeint(env, result_uuid_j);
    if (env->ExceptionCheck()) {
      return nullptr;
    }
    duckdb::FetchRequestMessage msg(connection_id, uuid);
    duckdb::MemoryStream stream;
    msg.ToMemoryStream(stream);
    return to_jbyte_array(env, stream);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeFetchRequest");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_serializeDisconnect(
    JNIEnv* env, jobject, jstring connection_id_j) {
  try {
    auto connection_id = jstring_to_std(env, connection_id_j);
    duckdb::DisconnectMessage msg(connection_id);
    duckdb::MemoryStream stream;
    msg.ToMemoryStream(stream);
    return to_jbyte_array(env, stream);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeDisconnect");
    return nullptr;
  }
}

namespace {

// Parses a serialized QuackMessage from `bytes_j`. Uses
// GetPrimitiveArrayCritical to pin the JVM buffer without a copy. On
// success, returns a non-null `unique_ptr` and leaves no pending Java
// exception. On failure (null buffer / malformed bytes / upstream throw),
// returns nullptr with a Java RuntimeException pending; callers MUST
// return immediately without touching the return value.
std::unique_ptr<duckdb::QuackMessage> parse_message(JNIEnv* env, jbyteArray bytes_j) {
  if (bytes_j == nullptr) {
    throw_runtime(env, "response bytes are null");
    return nullptr;
  }
  jsize len = env->GetArrayLength(bytes_j);
  jbyte* raw = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(bytes_j, nullptr));
  if (raw == nullptr) {
    throw_runtime(env, "could not pin response bytes");
    return nullptr;
  }
  std::unique_ptr<duckdb::QuackMessage> msg;
  try {
    duckdb::MemoryStream s(reinterpret_cast<duckdb::data_ptr_t>(raw),
                           static_cast<duckdb::idx_t>(len));
    msg = duckdb::QuackMessage::FromMemoryStream(s);
    env->ReleasePrimitiveArrayCritical(bytes_j, raw, JNI_ABORT);
  } catch (const std::exception& e) {
    env->ReleasePrimitiveArrayCritical(bytes_j, raw, JNI_ABORT);
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    env->ReleasePrimitiveArrayCritical(bytes_j, raw, JNI_ABORT);
    throw_runtime(env, "unknown C++ exception while parsing QuackMessage");
    return nullptr;
  }
  return msg;
}

// Converts a duckdb::hugeint_t {int64 upper, uint64 lower} to a Java
// java.math.BigInteger by writing a 17-byte big-endian buffer (one leading
// 0x00 sign byte + 8 bytes upper big-endian + 8 bytes lower big-endian)
// and invoking the BigInteger(byte[]) constructor. The leading 0x00 byte
// forces a positive interpretation regardless of the high bit of `upper`,
// matching the wire's semantics where `result_uuid` is an opaque 128-bit
// identifier. Returns nullptr with a pending Java exception on JNI
// lookup failure.
jobject hugeint_to_jbiginteger(JNIEnv* env, duckdb::hugeint_t v) {
  jclass cls = env->FindClass("java/math/BigInteger");
  if (cls == nullptr) {
    return nullptr;
  }
  jmethodID ctor = env->GetMethodID(cls, "<init>", "([B)V");
  if (ctor == nullptr) {
    return nullptr;
  }
  jbyteArray buf = env->NewByteArray(17);
  if (buf == nullptr) {
    return nullptr;
  }
  jbyte bytes[17];
  bytes[0] = 0; // sign byte forces positive interpretation
  uint64_t upper_u = static_cast<uint64_t>(v.upper);
  uint64_t lower_u = v.lower;
  for (int i = 0; i < 8; ++i) {
    bytes[1 + i] = static_cast<jbyte>((upper_u >> (8 * (7 - i))) & 0xff);
    bytes[9 + i] = static_cast<jbyte>((lower_u >> (8 * (7 - i))) & 0xff);
  }
  env->SetByteArrayRegion(buf, 0, 17, bytes);
  return env->NewObject(cls, ctor, buf);
}

void throw_unexpected_type(JNIEnv* env, const char* expected, duckdb::MessageType actual) {
  std::string msg = "expected ";
  msg += expected;
  msg += ", got ";
  msg += std::to_string(static_cast<int>(actual));
  throw_runtime(env, msg.c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_parseMessageType(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return -1;
  }
  return static_cast<jint>(msg->Type());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_extractConnectionId(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return nullptr;
  }
  try {
    if (msg->Type() != duckdb::MessageType::CONNECTION_RESPONSE) {
      throw_unexpected_type(env, "CONNECTION_RESPONSE", msg->Type());
      return nullptr;
    }
    const auto& resp = msg->Cast<duckdb::ConnectionResponseMessage>();
    return env->NewStringUTF(resp.ConnectionId().c_str());
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in extractConnectionId");
    return nullptr;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_extractErrorMessage(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return nullptr;
  }
  try {
    if (msg->Type() != duckdb::MessageType::ERROR_RESPONSE) {
      throw_unexpected_type(env, "ERROR_RESPONSE", msg->Type());
      return nullptr;
    }
    const auto& resp = msg->Cast<duckdb::ErrorResponse>();
    return env->NewStringUTF(resp.ErrorMessage().c_str());
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in extractErrorMessage");
    return nullptr;
  }
}

// Only `PrepareResponseMessage` carries `needs_more_fetch` upstream
// (see quack_message.hpp:165 `NeedsMoreFetch`). `FetchResponseMessage` does
// not expose a needs-more-fetch flag in the current submodule pin - it
// only carries `results` + optional `batch_index`. Until upstream changes
// that, we accept PREPARE_RESPONSE only; passing a FETCH_RESPONSE here is
// a programming error and we surface it as a Java RuntimeException.
extern "C" JNIEXPORT jboolean JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_needsMoreFetch(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return JNI_FALSE;
  }
  try {
    if (msg->Type() != duckdb::MessageType::PREPARE_RESPONSE) {
      throw_unexpected_type(env, "PREPARE_RESPONSE", msg->Type());
      return JNI_FALSE;
    }
    const auto& resp = msg->Cast<duckdb::PrepareResponseMessage>();
    return resp.NeedsMoreFetch() ? JNI_TRUE : JNI_FALSE;
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return JNI_FALSE;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in needsMoreFetch");
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_extractResultUuid(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return nullptr;
  }
  try {
    if (msg->Type() != duckdb::MessageType::PREPARE_RESPONSE) {
      throw_unexpected_type(env, "PREPARE_RESPONSE", msg->Type());
      return nullptr;
    }
    const auto& resp = msg->Cast<duckdb::PrepareResponseMessage>();
    return hugeint_to_jbiginteger(env, resp.ResultUUID());
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in extractResultUuid");
    return nullptr;
  }
}

// Returns the number of `DataChunkWrapper` entries in a FETCH_RESPONSE.
// Quack's wire does not carry an explicit "no more data" flag on
// `FetchResponseMessage` (only `PrepareResponseMessage` has
// `needs_more_fetch`). The upstream client (`duckdb-quack/src/quack_scan.cpp:331`)
// signals end-of-FETCH-loop with:
//
//   if (fetch_response->MutableResults().empty()) {
//     // server is done, we are done
//     global_state.needs_more_fetch = false;
//     return;
//   }
//
// So we mirror that: the Scala driver pumps FETCH until this returns 0.
extern "C" JNIEXPORT jint JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_fetchResponseChunkCount(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return -1;
  }
  try {
    if (msg->Type() != duckdb::MessageType::FETCH_RESPONSE) {
      throw_unexpected_type(env, "FETCH_RESPONSE", msg->Type());
      return -1;
    }
    auto& resp = msg->Cast<duckdb::FetchResponseMessage>();
    return static_cast<jint>(resp.MutableResults().size());
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return -1;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in fetchResponseChunkCount");
    return -1;
  }
}

// Returns the column names from a PREPARE_RESPONSE (`result_names`,
// declared at upstream `quack_message.hpp:181` and exposed via
// `PrepareResponseMessage::Names() -> const vector<string>&`,
// lines 157-159). FETCH_RESPONSE has no schema and therefore no names;
// the driver pulls names from the PREPARE_RESPONSE once and reuses them
// for every subsequent FETCH on the same connection.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_extractColumnNames(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return nullptr;
  }
  try {
    if (msg->Type() != duckdb::MessageType::PREPARE_RESPONSE) {
      throw_unexpected_type(env, "PREPARE_RESPONSE", msg->Type());
      return nullptr;
    }
    const auto& resp = msg->Cast<duckdb::PrepareResponseMessage>();
    const auto& names = resp.Names();
    jclass string_cls = env->FindClass("java/lang/String");
    if (string_cls == nullptr) {
      return nullptr;
    }
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(names.size()), string_cls, nullptr);
    if (out == nullptr) {
      return nullptr;
    }
    for (size_t i = 0; i < names.size(); ++i) {
      jstring s = env->NewStringUTF(names[i].c_str());
      if (s == nullptr) {
        return nullptr;
      }
      env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    return out;
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in extractColumnNames");
    return nullptr;
  }
}

// =====================================================================
// Arrow C-data ArrowArrayStream extraction (Task 5)
// =====================================================================
//
// Converts a PREPARE_RESPONSE or FETCH_RESPONSE message's
// `vector<unique_ptr<DataChunkWrapper>>` payload into a heap-allocated
// `ArrowArrayStream*` exposing the C-data ABI. The Java side imports it
// via `org.apache.arrow.c.Data.importArrayStream`. The Arrow Java
// importer takes ownership: it calls `stream->release` (which deletes
// our holder) when the ArrowReader is closed.
//
// Upstream API used (verified at
//   /opt/homebrew/include/duckdb/common/arrow/arrow_converter.hpp:20-24):
//
//   static void ArrowConverter::ToArrowSchema(
//       ArrowSchema* out_schema,
//       const vector<LogicalType>& types,
//       const vector<string>& names,
//       ClientProperties& options);
//
//   static void ArrowConverter::ToArrowArray(
//       DataChunk& input,
//       ArrowArray* out_array,
//       ClientProperties options,
//       const unordered_map<idx_t, const shared_ptr<ArrowTypeExtensionData>>&
//           extension_type_cast);
//
// `ClientProperties` (client_properties.hpp:18-37) has a defaulted no-arg
// ctor and sensible defaults (UTC time zone, REGULAR offset size, no
// extension type sets), so a default instance is fine for the gateway's
// wire path. If future quack nodes ship strings >2GB or list views, this
// is where we would surface those flags.
namespace {

// `ArrowConverter::ToArrowSchema` and `ToArrowArray` reach into
// `ClientProperties::client_context` for several code paths (notably
// when emitting types whose serialization depends on a connection-level
// setting). A default-constructed `ClientProperties` has a null
// `optional_ptr<ClientContext>`, which surfaces at runtime as
// `InternalException("Attempting to dereference an optional pointer
// that is not set")`. Construct one in-memory DuckDB once per process
// and reuse its ClientContext for every stream we hand out - the
// instance is otherwise unused (no SQL is ever run on it).
//
// This is the cheapest viable workaround. A v2 would let the gateway
// hand in a real ClientContext from its own DuckLake-backed connection.
duckdb::ClientProperties& shared_client_properties() {
  static std::mutex mtx;
  static std::unique_ptr<duckdb::DuckDB> db;
  static std::unique_ptr<duckdb::Connection> conn;
  static std::unique_ptr<duckdb::ClientProperties> props;
  std::lock_guard<std::mutex> lock(mtx);
  if (!props) {
    db = std::make_unique<duckdb::DuckDB>(nullptr);
    conn = std::make_unique<duckdb::Connection>(*db);
    props = std::make_unique<duckdb::ClientProperties>(conn->context->GetClientProperties());
  }
  return *props;
}

struct ChunkStreamHolder {
  // `duckdb::vector` is what `Mutable*Results()` returns - keep the same
  // alias so we can `std::move` straight in without a transcribe loop.
  duckdb::vector<duckdb::unique_ptr<duckdb::DataChunkWrapper>> chunks;
  duckdb::vector<duckdb::LogicalType> types;
  // Column names, populated from `PrepareResponseMessage::Names()` for a
  // PREPARE_RESPONSE; synthesised as `column_0..N-1` for FETCH_RESPONSE
  // (which carries no schema). The Scala driver actually never sees a
  // FETCH_RESPONSE-derived schema in production - it only consults the
  // schema once, from the initial PREPARE_RESPONSE-derived reader - but
  // synthesising names keeps a stand-alone test of `extractArrowStream`
  // on a FETCH_RESPONSE fixture working.
  duckdb::vector<duckdb::string> names;
  size_t next = 0;
  // Copy of the shared process-wide ClientProperties; copying is cheap
  // (a few scalars + a shared_ptr - the client_context optional_ptr is
  // observer-style and does not refcount).
  duckdb::ClientProperties properties = shared_client_properties();
  std::string last_error;
};

int chunk_stream_get_schema(struct ArrowArrayStream* stream, struct ArrowSchema* out) {
  auto* h = static_cast<ChunkStreamHolder*>(stream->private_data);
  if (h == nullptr) {
    return EIO;
  }
  try {
    duckdb::ArrowConverter::ToArrowSchema(out, h->types, h->names, h->properties);
    return 0;
  } catch (const std::exception& e) {
    h->last_error = e.what();
    return EIO;
  } catch (...) {
    h->last_error = "unknown C++ exception in get_schema";
    return EIO;
  }
}

int chunk_stream_get_next(struct ArrowArrayStream* stream, struct ArrowArray* out) {
  auto* h = static_cast<ChunkStreamHolder*>(stream->private_data);
  if (h == nullptr) {
    return EIO;
  }
  try {
    // End-of-stream signal per the Arrow C-data ABI: return 0 with a
    // released (zeroed) array. The Arrow Java importer treats that as
    // EOF and stops iterating.
    if (h->next >= h->chunks.size()) {
      out->release = nullptr;
      return 0;
    }
    auto& chunk = h->chunks[h->next]->Chunk();
    // ArrowConverter::ToArrowArray takes
    //   const unordered_map<idx_t, const shared_ptr<ArrowTypeExtensionData>>&
    // where both aliases live in the `duckdb::` namespace. Building an
    // empty instance is enough: we have no DuckDB extension types on the
    // wire today (no GEOMETRY/UNION/etc).
    static const duckdb::unordered_map<duckdb::idx_t,
                                       const duckdb::shared_ptr<duckdb::ArrowTypeExtensionData>>
        kNoExtensions;
    duckdb::ArrowConverter::ToArrowArray(chunk, out, h->properties, kNoExtensions);
    h->next++;
    return 0;
  } catch (const std::exception& e) {
    h->last_error = e.what();
    return EIO;
  } catch (...) {
    h->last_error = "unknown C++ exception in get_next";
    return EIO;
  }
}

const char* chunk_stream_get_last_error(struct ArrowArrayStream* stream) {
  auto* h = static_cast<ChunkStreamHolder*>(stream->private_data);
  if (h == nullptr || h->last_error.empty()) {
    return nullptr;
  }
  return h->last_error.c_str();
}

void chunk_stream_release(struct ArrowArrayStream* stream) {
  if (stream == nullptr) {
    return;
  }
  if (stream->private_data != nullptr) {
    delete static_cast<ChunkStreamHolder*>(stream->private_data);
    stream->private_data = nullptr;
  }
  stream->release = nullptr;
}

// Wires the four C-data callbacks. The Arrow C-data ABI explicitly
// allows `private_data` to outlive the stream until `release` runs, so
// holding the DataChunkWrappers via unique_ptr is the right ownership
// model.
void wire_chunk_stream(ArrowArrayStream* stream, ChunkStreamHolder* holder) {
  stream->get_schema = chunk_stream_get_schema;
  stream->get_next = chunk_stream_get_next;
  stream->get_last_error = chunk_stream_get_last_error;
  stream->release = chunk_stream_release;
  stream->private_data = holder;
}

// Pulls the result chunks out of either PREPARE_RESPONSE or
// FETCH_RESPONSE. Returns nullptr with a pending Java exception on a
// wrong message type so callers can propagate.
ChunkStreamHolder* steal_results_into_holder(JNIEnv* env, duckdb::QuackMessage& msg) {
  auto holder = std::make_unique<ChunkStreamHolder>();
  if (msg.Type() == duckdb::MessageType::PREPARE_RESPONSE) {
    auto& resp = msg.Cast<duckdb::PrepareResponseMessage>();
    holder->types = resp.Types();
    holder->names = resp.Names();
    holder->chunks = std::move(resp.MutableResults());
  } else if (msg.Type() == duckdb::MessageType::FETCH_RESPONSE) {
    auto& resp = msg.Cast<duckdb::FetchResponseMessage>();
    holder->chunks = std::move(resp.MutableResults());
    // FetchResponseMessage carries no schema; derive it from the first
    // chunk. An empty fetch response has no schema and no rows.
    if (!holder->chunks.empty()) {
      holder->types = holder->chunks.front()->Chunk().GetTypes();
      holder->names.reserve(holder->types.size());
      for (size_t i = 0; i < holder->types.size(); ++i) {
        holder->names.push_back("column_" + std::to_string(i));
      }
    }
  } else {
    throw_unexpected_type(env, "PREPARE_RESPONSE or FETCH_RESPONSE", msg.Type());
    return nullptr;
  }
  return holder.release();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ai_starlake_quack_edge_adapter_QuackNativeBridge_00024_extractArrowStream(
    JNIEnv* env, jobject, jbyteArray bytes_j) {
  auto msg = parse_message(env, bytes_j);
  if (msg == nullptr) {
    return 0;
  }
  try {
    auto* holder = steal_results_into_holder(env, *msg);
    if (holder == nullptr) {
      return 0; // pending Java exception
    }
    auto* stream = new ArrowArrayStream{};
    wire_chunk_stream(stream, holder);
    return reinterpret_cast<jlong>(stream);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return 0;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in extractArrowStream");
    return 0;
  }
}

// =====================================================================
// Test-only JNI fixture helpers (Task 5.3)
// =====================================================================
//
// These exist purely so the Scala test suite can construct serialized
// response-side QuackMessages and exercise extractArrowStream + the
// PREPARE_RESPONSE / FETCH_RESPONSE / CONNECTION_RESPONSE /
// ERROR_RESPONSE happy-path decoders. They are bound by a sibling
// `QuackTestFixtures` Scala object that lives under src/test/scala/.
// Both objects load the same libquackwire.dylib (a JVM-level singleton
// in `System.load`), so we just publish more symbols on the same .dylib
// rather than ship a separate test library.
namespace {

// Builds a single 1-row x 1-col INTEGER DataChunk with cell value `42`.
// Returned by-value; the caller wraps it in a DataChunkWrapper before
// handing it to the response message ctor, because
// DataChunkWrapper::Chunk() needs to outlive the wrapper.
//
// Use `duckdb::vector` (not `std::vector`) because the upstream DataChunk
// and message ctors take the duckdb alias, which is a distinct type from
// `std::vector` even though it derives from it.
std::unique_ptr<duckdb::DataChunk> make_one_row_one_col_int_chunk() {
  duckdb::vector<duckdb::LogicalType> types;
  types.push_back(duckdb::LogicalType::INTEGER);
  auto chunk = std::make_unique<duckdb::DataChunk>();
  chunk->Initialize(duckdb::Allocator::DefaultAllocator(), types);
  chunk->SetValue(0, 0, duckdb::Value::INTEGER(42));
  chunk->SetCardinality(1);
  return chunk;
}

jbyteArray serialize_to_jbyte_array(JNIEnv* env, const duckdb::QuackMessage& msg) {
  duckdb::MemoryStream stream;
  msg.ToMemoryStream(stream);
  return to_jbyte_array(env, stream);
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_serializeSampleConnectionResponse(
    JNIEnv* env, jobject, jstring connection_id_j) {
  try {
    auto connection_id = jstring_to_std(env, connection_id_j);
    duckdb::ConnectionResponseMessage msg(connection_id);
    return serialize_to_jbyte_array(env, msg);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeSampleConnectionResponse");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_serializeSampleErrorResponse(
    JNIEnv* env, jobject, jstring message_j) {
  try {
    auto message = jstring_to_std(env, message_j);
    duckdb::ErrorResponse msg(message);
    return serialize_to_jbyte_array(env, msg);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeSampleErrorResponse");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_serializeSamplePrepareResponse(
    JNIEnv* env,
    jobject,
    jobject result_uuid_j,
    jboolean needs_more_fetch_j,
    jboolean with_one_row_one_col_chunk_j,
    jstring column_name_j) {
  try {
    duckdb::hugeint_t uuid = jbiginteger_to_hugeint(env, result_uuid_j);
    if (env->ExceptionCheck()) {
      return nullptr;
    }
    duckdb::vector<duckdb::LogicalType> types;
    types.push_back(duckdb::LogicalType::INTEGER);
    duckdb::vector<duckdb::string> names;
    // jstring -> std::string -> duckdb::string. Empty / null column name
    // falls back to the existing default "column_0" so the older two-arg
    // call sites (Task 4/5 specs that don't care about names) keep
    // working without touching them.
    std::string cname = jstring_to_std(env, column_name_j);
    if (cname.empty()) {
      cname = "column_0";
    }
    names.push_back(cname);
    duckdb::vector<duckdb::unique_ptr<duckdb::DataChunkWrapper>> chunks;
    std::unique_ptr<duckdb::DataChunk> backing_chunk;
    if (with_one_row_one_col_chunk_j == JNI_TRUE) {
      backing_chunk = make_one_row_one_col_int_chunk();
      chunks.push_back(duckdb::make_uniq<duckdb::DataChunkWrapper>(*backing_chunk));
    }
    duckdb::PrepareResponseMessage msg(
        types, names, std::move(chunks), needs_more_fetch_j == JNI_TRUE, uuid);
    return serialize_to_jbyte_array(env, msg);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeSamplePrepareResponse");
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_serializeSampleFetchResponse(
    JNIEnv* env, jobject, jboolean with_one_row_one_col_chunk_j) {
  try {
    duckdb::vector<duckdb::unique_ptr<duckdb::DataChunkWrapper>> chunks;
    std::unique_ptr<duckdb::DataChunk> backing_chunk;
    if (with_one_row_one_col_chunk_j == JNI_TRUE) {
      backing_chunk = make_one_row_one_col_int_chunk();
      chunks.push_back(duckdb::make_uniq<duckdb::DataChunkWrapper>(*backing_chunk));
    }
    duckdb::FetchResponseMessage msg(std::move(chunks));
    return serialize_to_jbyte_array(env, msg);
  } catch (const std::exception& e) {
    throw_runtime(env, e.what());
    return nullptr;
  } catch (...) {
    throw_runtime(env, "unknown C++ exception in serializeSampleFetchResponse");
    return nullptr;
  }
}