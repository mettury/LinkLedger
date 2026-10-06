# Source provenance and repository import

This repository imports the verified LinkLedger source distribution created on 2026-10-06. The archive was checked against its recorded SHA-256 and all included manifest entries before preparing this repository.

- Source archive: `LinkLedger-source-project.zip`
- Source archive SHA-256: `5e651be212a8ec09007357f8014297d4467dd99ac9299b2a6be056c5c06d59cd`
- Original source archive size: 188,271 bytes
- Historical reviewed executable SHA-256: `bb3094d11ae6b51e63eab61ac58efc467e585ce8a03005f19d75d4dab703eddf`

The delivered source archive deliberately excluded Git's object database. Original local Git objects were therefore not available for publication. The historical baseline commit identifier recorded in the engineering evidence is a record of that build session; it is not presented as a commit that resolves in this repository. No historical commit, authorship, timestamp or human approval was recreated.

The actual pre-alias source is preserved as readable files in [the baseline snapshot](evidence/greenfield-baseline/). Its original archived form had SHA-256 `a35f22893cfedce85856742ef1009d5ce7b47f0a6ba32ac3d3a973fb1ca163f4`. The files were extracted without content changes. Baseline test logs, the deliberate red test stage, scoped diffs, final test results and independent runtime evidence remain available in [the validation record](VALIDATION.md).

Publication preparation changes only repository packaging and documentation: executable binaries and the ZIP container are omitted; the baseline is expanded; wrapper executable permissions are restored; ignore rules protect generated outputs and local credentials; documentation points to the source build and retained evidence. Application and test source content is unchanged from the verified source distribution.

The publication commit is a present-day source import. It does not claim that a human personally wrote every line or approved the implementation. Human ownership and release review remain the gates described in the engineering record.
