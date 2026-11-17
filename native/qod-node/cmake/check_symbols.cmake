# Symbol gates of the test suite (ctest, CMakeLists.txt): what a symbol table holds, read with one tool, and
# never a silent pass. Run as a script, the same on every platform, so no shell or PowerShell quoting stands
# between CTest and the tool (a PowerShell -Command holding semicolons was split by CTest).
#
#   cmake -DTOOL=<nm | llvm-nm | dumpbin> [-DTOOL_ARG=<one argument, e.g. /symbols>] -DCHECKS=<n>
#         -DFILE_<i>=<binary or library> [-DREQUIRE_<i>=<a>+<b>] [-DFORBID_<i>=<c>+<d>]  (i = 1..n)
#         -P check_symbols.cmake
#
# For each file: the tool must succeed and print something, every REQUIRE substring must appear in its output
# (the positive control: the tool did read the symbols) and no FORBID substring may.
if(NOT TOOL OR NOT CHECKS)
  message(FATAL_ERROR "check_symbols.cmake needs -DTOOL= and -DCHECKS=")
endif()
foreach(i RANGE 1 ${CHECKS})
  set(file "${FILE_${i}}")
  if(NOT file OR NOT EXISTS "${file}")
    message(FATAL_ERROR "check ${i}: no file '${file}'")
  endif()
  execute_process(COMMAND "${TOOL}" ${TOOL_ARG} "${file}"
                  OUTPUT_VARIABLE out ERROR_VARIABLE err RESULT_VARIABLE rc)
  if(NOT rc EQUAL 0)
    message(FATAL_ERROR "check ${i}: ${TOOL} failed on ${file} (${rc}): ${err}")
  endif()
  string(LENGTH "${out}" out_length)
  if(out_length EQUAL 0)
    message(FATAL_ERROR "check ${i}: ${TOOL} printed nothing for ${file} (a stripped binary is not checked)")
  endif()
  if(REQUIRE_${i})
    string(REPLACE "+" ";" required "${REQUIRE_${i}}")
    foreach(name IN LISTS required)
      string(FIND "${out}" "${name}" at)
      if(at EQUAL -1)
        message(FATAL_ERROR "check ${i}: ${file} holds no symbol naming ${name} (read with ${TOOL})")
      endif()
    endforeach()
  endif()
  if(FORBID_${i})
    string(REPLACE "+" ";" forbidden "${FORBID_${i}}")
    foreach(name IN LISTS forbidden)
      string(FIND "${out}" "${name}" at)
      if(NOT at EQUAL -1)
        math(EXPR from "${at} - 80")
        if(from LESS 0)
          set(from 0)
        endif()
        string(SUBSTRING "${out}" ${from} 200 excerpt)
        message(FATAL_ERROR "check ${i}: ${file} holds a symbol naming ${name}: ${excerpt}")
      endif()
    endforeach()
  endif()
  message(STATUS "check ${i}: ${file} OK (${TOOL})")
endforeach()
