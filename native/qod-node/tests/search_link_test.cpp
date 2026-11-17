// QoD Search (spec 5.1): qod-node's build links the Rust qod_search_core and calls it through the C ABI.
#include "qod_search.h"

#include <cstring>
#include <iostream>
#include <string>

int main() {
	// A query check through the library: rule 6 refuses a field outside the call's columns.
	QsField fields[] = {{"body", 1}};
	char *err = nullptr;
	if (qs_check_query("w1 w2", fields, 1, &err) != 0) {
		std::cerr << "FAIL: qs_check_query refused a plain query: " << (err ? err : "") << std::endl;
		qs_string_free(err);
		return 1;
	}
	if (qs_check_query("title:w1", fields, 1, &err) == 0 || !err ||
	    std::string(err).rfind("search_field_not_allowed", 0) != 0) {
		std::cerr << "FAIL: qs_check_query admitted title:w1 with columns [body]" << std::endl;
		qs_string_free(err);
		return 1;
	}
	qs_string_free(err);
	if (QS_BUNDLE_FORMAT_VERSION != 3) {
		std::cerr << "FAIL: bundle format " << QS_BUNDLE_FORMAT_VERSION << ", expected 3" << std::endl;
		return 1;
	}
	std::cout << "qod_search_core linked: qs_check_query answers, bundle format 3" << std::endl;
	return 0;
}
