from conan import ConanFile
from conan.tools.cmake import CMake, CMakeToolchain, cmake_layout


class SunradarConan(ConanFile):
    name = "sunradar"
    version = "0.1.0"
    settings = "os", "arch", "compiler", "build_type"
    options = {"with_tests": [True, False]}
    default_options = {"with_tests": True}
    generators = "CMakeDeps"

    def requirements(self):
        # The core has no runtime dependencies yet. Eigen comes in M2.
        pass

    def build_requirements(self):
        if self.options.with_tests:
            self.test_requires("catch2/3.16.1")

    def layout(self):
        cmake_layout(self)

    def generate(self):
        tc = CMakeToolchain(self)
        tc.cache_variables["SUNRADAR_BUILD_TESTS"] = bool(self.options.with_tests)
        tc.generate()

    def build(self):
        cmake = CMake(self)
        cmake.configure()
        cmake.build()
        if self.options.with_tests and not self.conf.get("tools.build:skip_test", default=False):
            cmake.test()
