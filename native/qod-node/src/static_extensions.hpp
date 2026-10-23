#pragma once

namespace qod {

//! Static builds only. Registers what the official static libraries link like extensions, before any
//! database opens: the loadable_extensions and httplib capabilities (without them INSTALL and LOAD are
//! refused) and the built-in extensions the release shared library also carries. Referencing each
//! describe function is also what keeps the linker from dropping its archive member. Throws if DuckDB
//! refuses one.
void RegisterStaticExtensions();

} // namespace qod
