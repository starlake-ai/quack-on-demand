#include "function_classes.hpp"

#include <map>
#include <set>
#include <string>
#include <utility>

namespace qod {
namespace {

struct Entry {
	const char *name;
	const char *schema;
	const char *type;
	const char *cls;
};

const Entry kEntries[] = {
#define QOD_FUNCTION_CLASS(name, schema, type, cls) {name, schema, type, cls},
#include "function_classes.inc"
#undef QOD_FUNCTION_CLASS
};

FunctionClass ParseClass(const std::string &cls) {
	if (cls == "allowed") {
		return FunctionClass::Allowed;
	}
	if (cls == "listing") {
		return FunctionClass::Listing;
	}
	if (cls == "file") {
		return FunctionClass::File;
	}
	if (cls == "superuser") {
		return FunctionClass::Superuser;
	}
	// FunctionClassesHeaderSpec rules this out; refuse rather than admit.
	return FunctionClass::Superuser;
}

struct Classes {
	std::map<std::pair<std::string, std::string>, FunctionClass> by_name_and_type;
	std::set<std::string> superuser;
};

const Classes &AllClasses() {
	static const Classes classes = [] {
		Classes result;
		for (auto &entry : kEntries) {
			auto cls = ParseClass(entry.cls);
			result.by_name_and_type[{entry.name, entry.type}] = cls;
			if (cls == FunctionClass::Superuser) {
				result.superuser.insert(entry.name);
			}
		}
		return result;
	}();
	return classes;
}

} // namespace

std::optional<FunctionClass> FunctionClassOf(const std::string &name, const std::string &type) {
	auto &classes = AllClasses().by_name_and_type;
	auto found = classes.find({name, type});
	if (found == classes.end()) {
		return std::nullopt;
	}
	return found->second;
}

bool IsSuperuserFunction(const std::string &name) {
	return AllClasses().superuser.count(name) != 0;
}

} // namespace qod
