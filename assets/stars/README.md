# The star data

`stars.json` is the sky of Sternstunde (`PLAN.md` 14.2): 5080 stars down to magnitude 6.0 (and the
fainter stars of the constellation lines), 492 proper names, and the lines and names of the 89
parts of the 88 constellations (Serpens has two parts).

Do not edit it by hand. `scripts/stars/build_stars.py` makes it, and its docstring describes the
format.

## Sources and licenses

- **Stars:** the Yale Bright Star Catalogue, 5th revised edition (Hoffleit D., Warren Jr W.H.,
  Astronomical Data Center, NSSDC/ADC, 1991; VizieR V/50), from the copy at
  <http://tdc-www.harvard.edu/catalogs/bsc5.html>. The catalogue has no license file. The ADC
  allows the distribution with a program and asks for the acknowledgement of the ADC and of the
  authors (an answer of the ADC, quoted in the copyright file of the Debian package `yale`).
- **Constellation lines, constellation names, and star names:** d3-celestial, tag `v0.7.32`,
  Copyright (c) 2015, Olaf Frohn, under the BSD 3-Clause License. The lines are matched to the
  stars of the catalogue, and the names to their HD numbers.

The apps show the attribution of `stars.json`: "Stars: Yale Bright Star Catalogue, 5th ed.
(Hoffleit & Warren 1991, NASA ADC). Constellations and star names: d3-celestial (c) 2015 Olaf
Frohn, BSD-3."

## The license of d3-celestial

```
Copyright (c) 2015, Olaf Frohn
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of
   conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of
   conditions and the following disclaimer in the documentation and/or other materials provided
   with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to
   endorse or promote products derived from this software without specific prior written
   permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
