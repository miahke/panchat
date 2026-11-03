import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("package_check", Path(__file__).parents[1] / "verify-package.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf(machine=183, alignment=16384):
    data = bytearray(128)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", data, 16, 3, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)


class PackageTest(unittest.TestCase):
    def package(self, directory, missing=None, machine=183, alignment=16384):
        path = Path(directory) / "synthetic.apk"
        with zipfile.ZipFile(path, "w") as apk:
            for name in checker.COMPONENTS:
                if name != missing:
                    apk.writestr("lib/arm64-v8a/" + name, elf(machine, alignment))
        return path

    def test_removed_cups_engine_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=self.package(directory)
            with zipfile.ZipFile(path,'a') as archive:archive.writestr('lib/arm64-v8a/libbitchat_cups.so',elf())
            with self.assertRaisesRegex(ValueError,'Removed Cups'):checker.verify(path,['arm64-v8a'])

    def test_missing_native_backend_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "libbitchat_vk"):
                checker.verify(self.package(directory, missing="libbitchat_vk.so"), ["arm64-v8a"])

    def test_wrong_architecture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "architecture"):
                checker.verify(self.package(directory, machine=62), ["arm64-v8a"])

    def test_four_kilobyte_alignment_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "alignment"):
                checker.verify(self.package(directory, alignment=4096), ["arm64-v8a"])

    def test_complete_native_package_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = checker.verify(self.package(directory), ["arm64-v8a"])
            self.assertEqual(3, len(report["arm64-v8a"]))

    def test_32_bit_platform_page_size_is_supported(self):
        data = bytearray(96)
        data[:6] = b"\x7fELF\x01\x01"
        struct.pack_into("<HH", data, 16, 3, 40)
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<I", data, 52, 1)
        struct.pack_into("<I", data, 80, 4096)
        self.assertEqual(64, len(checker.verify_elf(bytes(data), "armeabi-v7a", "synthetic.so")))


if __name__ == "__main__":
    unittest.main()
