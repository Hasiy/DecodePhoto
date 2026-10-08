# LibRaw Android build

This project vendors the public headers, internal headers, and `src` directory
from the official LibRaw 0.22.1 release. `CMakeLists.txt` compiles those sources
as a position-independent static library and links it into
`libraw_android_bridge.so` for every configured Android ABI.

The version record is named `LIBRAW_VERSION.txt` instead of `VERSION` because a
root-level `VERSION` file shadows the standard C++ `<version>` header on
case-insensitive Windows filesystems.

The three placeholder translation units ending in `_ph.cpp` are deliberately
excluded because this project builds the real preprocessing, postprocessing,
and writer implementations from the same release.

Optional JPEG, LCMS, OpenMP, RawSpeed, and DNG SDK integrations are not enabled.
The built-in LibRaw DNG and RAF paths used by this sample do not require them.
The application chooses the LGPL 2.1 option for this unmodified library. Before
shipping a product, review dynamic/static-linking obligations and expose the
applicable notice and source offer through the product's legal UI.
