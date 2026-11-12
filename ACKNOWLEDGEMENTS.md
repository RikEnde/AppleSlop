# Acknowledgements

AppleIIslop relies on the people who designed the Apple II, documented its
hardware, preserved its software, and made independent tests of the 6502.
These are the sources used by the implementation and its tests.

## The machine and its software

- **Steve Wozniak**: the Apple II hardware and Disk II controller whose
  behaviour this emulator reproduces. The Computer History Museum documents
  [the Apple II's design](https://www.computerhistory.org/revolution/personal-computers/17/300)
  and [the development of its disk system](https://computerhistory.org/blog/apple-ii-dos-source-code/).
- **Paul Laughton**, at Shepardson Microsystems: the original Apple II DOS.
  **Steve Wozniak and Randy Wigginton** wrote its early low-level disk routines.
  Their work underlies the DOS family; this project's disk integration tests
  boot DOS 3.3 and exercise its real save and load routines. The
  [Computer History Museum's DOS history and source archive](https://computerhistory.org/blog/apple-ii-dos-source-code/)
  records their contributions.
- **John Brooks and contributors**: the continued development of
  [ProDOS 2.4.3](https://prodos8.com/releases/prodos-243/), whose disk image is
  included in the project. It is separate from the DOS 3.3 regression tests.
- The **Apple and Microsoft engineers** who developed the monitor ROMs,
  Applesoft BASIC and operating-system software run by the emulator.

## Hardware and disk documentation

- **Jim Sather**: *[Understanding the Apple II](https://archive.org/details/Understanding_the_Apple_II_1983_Quality_Software)*
  (Quality Software, 1983). The Language Card implementation cites chapter 5;
  the Disk II controller cites chapter 9 for the interface and drive behaviour.
- **Don Worth and Pieter Lechner**: *[Beneath Apple DOS](https://www.duxburysystems.org/downloads/library/texas/apple/manuals/beneathdos.pdf)*.
  Chapter 3 informs the disk address/data fields and 6-and-2 encoding in
  `Disk.kt`; chapter 6 is a reference for the controller in `DiskII.kt`.
- The authors of Apple's *Apple II Reference Manual*: chapter 4 supplies the
  memory map and soft-switch table used by `Apple2MemoryBus.kt` and
  `SoftSwitches.kt`.

## Emulator references

- **R. Belmont**: MAME's BSD-3-Clause
  [Apple II/II Plus driver](https://github.com/mamedev/mame/blob/master/src/mame/apple/apple2.cpp).
  Its cassette input handling was used to check the comparator polarity in
  `Cassette.kt`. The driver also credits **Mike Balfour and Nathan Woods** for
  earlier Apple II emulation work, and **Antoine Vignau and Peter Ferrie** for
  documentation and assistance.

## 6502 tests and references

- **Thomas Harte and contributors**:
  [SingleStepTests / 65x02](https://github.com/SingleStepTests/65x02), under the
  MIT licence. The 60,700 included cases check registers, memory and ordered
  bus accesses across all 256 opcodes.
- **Klaus Dormann**:
  [6502 functional test](https://github.com/Klaus2m5/6502_65C02_functional_tests),
  under GPL-3.0-or-later. Its binary, assembly source and licence are kept
  together as test fixtures; they are not included in the emulator library.
- **Norbert Landsteiner**:
  [6502 instruction set reference](https://www.masswerk.at/6502/6502_instruction_set.html),
  including the undocumented opcodes and their names.
- **Neil Parker**:
  [6502 opcode encoding](https://llx.com/Neil/a2/opcodes.html), referenced by
  the instruction table's explanation of opcode groups.
- **Michael Steil**:
  [How MOS 6502 Illegal Opcodes Really Work](https://www.pagetable.com/31),
  referenced for hardware instruction decoding and overlapping operations.

Pinned versions, checksums and licences for the test inputs are in the
[fixture documentation](cpu/src/jvmTest/resources/reference/README.md).

## Preservation

Thanks to the people maintaining the **Apple II Documentation Project**, the
**Asimov archive** and its **apple2.org.za mirror**, and the **Internet Archive**
for keeping manuals and software accessible. The local Oregon Trail example
was obtained from the
[Asimov mirror's MECC collection](https://mirrors.apple2.org.za/ftp.apple.asimov.net/images/educational/mecc/);
its game images are not bundled with this project.

The original download sources of the bundled ROM and operating-system images
were not recorded in this checkout, so no particular archive is credited as
their supplier. See [ROM inputs](roms/README.md) and [disk images](disks/README.md).

Acknowledgements do not change the licences of the source code, test fixtures
or binary images. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [LICENSES](LICENSES/).

If a credit is missing or inaccurate, please tell us.
