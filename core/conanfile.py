from pathlib import Path

from conan import ConanFile
from conan.tools.cmake import CMake, CMakeDeps, CMakeToolchain, cmake_layout
from conan.tools.files import copy


class SunradarConan(ConanFile):
    name = "sunradar"
    version = "0.1.0"
    url = "https://github.com/andiwand/sunradar"
    description = "Sun position, sun path and projection for a camera overlay"
    license = "MPL-2.0"

    settings = "os", "arch", "compiler", "build_type"
    options = {
        "shared": [True, False],
        "with_tests": [True, False],
        "with_python": [True, False],
        "with_jni": [True, False],
        "with_wasm": [True, False],
        "with_apple": [True, False],
    }
    default_options = {
        "shared": False,
        "with_tests": True,
        "with_python": False,
        "with_jni": False,
        "with_wasm": False,
        "with_apple": False,
    }

    exports_sources = [
        "LICENSE",
        "android/jni/*",
        "wasm/*",
        "apple/*",
        "cmake/*",
        "python/*",
        "src/*",
        "third_party/*",
        "CMakeLists.txt",
    ]

    def requirements(self):
        if self.options.with_python:
            self.requires("pybind11/3.0.1")

    def build_requirements(self):
        if self.options.with_tests:
            self.test_requires("catch2/3.16.1")

    def layout(self):
        cmake_layout(self)

    def generate(self):
        tc = CMakeToolchain(self)
        tc.cache_variables["SUNRADAR_TESTS"] = bool(self.options.with_tests)
        tc.cache_variables["SUNRADAR_PYTHON"] = bool(self.options.with_python)
        tc.cache_variables["SUNRADAR_JNI"] = bool(self.options.with_jni)
        tc.cache_variables["SUNRADAR_WASM"] = bool(self.options.with_wasm)
        tc.cache_variables["SUNRADAR_APPLE"] = bool(self.options.with_apple)
        tc.generate()
        CMakeDeps(self).generate()

    def build(self):
        cmake = CMake(self)
        cmake.configure()
        cmake.build()
        if self.options.with_tests and not self.conf.get(
            "tools.build:skip_test", default=False
        ):
            cmake.test()

    def package(self):
        copy(
            self,
            "LICENSE",
            self.source_folder,
            str(Path(self.package_folder) / "licenses"),
        )
        CMake(self).install(component="core")

    def package_info(self):
        self.cpp_info.libs = ["sunradar"]
