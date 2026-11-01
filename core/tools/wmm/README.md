# World Magnetic Model

`WMM2025.COF` is the coefficient file of the World Magnetic Model 2025, from NOAA
NCEI. The WMM is a work of the US government, and it is in the public domain.

- Source: <https://www.ncei.noaa.gov/products/world-magnetic-model/wmm-coefficients>
- Download: `WMM2025COF.zip`, dated 2024-12-17, SHA-256
  `2e76569370d081f2cd7919490218bd094ca9afde347b198eff5621e0af460d03`
- Validity: 2025.0 to 2030.0

`generate_wmm.py` converts the file to
`src/sunradar/internal/wmm_coefficients.cpp`. The test values of the same
download are in `test/data/WMM2025_TestValues.txt`.

For WMM2030:

1. Download the new coefficient file and the test values.
2. Replace `WMM2025.COF` and `test/data/WMM2025_TestValues.txt`.
3. Run `python3 tools/wmm/generate_wmm.py tools/wmm/WMM2030.COF`.
4. Update the validity in `src/sunradar/magnetic.hpp`, and run the tests.
