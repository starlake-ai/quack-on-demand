// Search Phase 0 (spec Q2): qod-node's build links the Rust qod_search_core and calls it.
#include "qod_search.h"
#include <iostream>

int main() {
	auto hits = qod_search_probe(3000);
	if (hits != 1000) {
		std::cerr << "FAIL: expected 1000 hits, got " << hits << std::endl;
		return 1;
	}
	std::cout << "qod_search_core linked, 1000 hits" << std::endl;
	return 0;
}
