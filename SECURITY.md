# Security Policy

`exeris-caps-rate-limiting` is a proof of concept. It has not had a security review, and nothing in it should be relied
on in production.

## Supported versions

| Version / branch | Security support |
| --- | --- |
| `main` (default branch) | Reports accepted |
| tagged releases | none exist yet |

No artefact has been published from this repository, so a report is about source on `main`.

## Scope

In scope is anything this cap decides or stores: its `@Provides` services, the data its repositories
persist, and the tokens or secrets it generates, hashes or compares. A defect in how a consuming cap or
an application applies a result belongs to that consumer's repository.

## Reporting a vulnerability

Use GitHub private vulnerability reporting on this repository
(<https://github.com/exeris-systems/exeris-caps-rate-limiting/security/advisories/new>), or email security@exeris.eu.
Do not open a public issue, discussion or pull request for a suspected vulnerability.

A useful report names the affected commit, the configuration and the input that trigger it, what you
observed and what you expected. Do not include real credentials, tokens or personal data; synthetic
values are enough to demonstrate a defect.

The organisation-wide terms for acknowledgement and disclosure are in the
[Exeris security policy](https://github.com/exeris-systems/.github/blob/main/SECURITY.md).
