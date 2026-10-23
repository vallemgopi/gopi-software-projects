# Gopi's Software Projects

A collection of software projects by [Gopi](https://github.com/vallemgopi), with source code, setup instructions, and technical documentation.

## Projects

| Project | Description | Stack |
| --- | --- | --- |
| [CodeMix VoiceDesk](CodeMix-VoiceDesk/README.md) | Multilingual complaint intake through text and voice, with structured extraction, human review, and synthetic-data evaluation. | React, TypeScript, Java, Spring Boot, Python, FastAPI, PostgreSQL, Docker |
| [QueryFence](QueryFence/README.md) | Natural-language retail analytics with validated query plans, explicit approval, role-based access, and read-only query execution. | Java 21, Spring Boot, PostgreSQL, React, TypeScript, Docker, optional Ollama |

## CodeMix VoiceDesk

![CodeMix VoiceDesk staff dashboard](CodeMix-VoiceDesk/voice-form-lab/docs/dashboard.png)

CodeMix VoiceDesk connects a public complaint form to a staff review workspace. Reviewers inspect extracted fields, preserve original predictions, and save corrections separately. Optional local speech recognition supports voice intake.

This is a local research and demonstration build. Its bundled evaluation data is synthetic. Internet hosting and a live telephone number are not configured.

**Twilio status:** A free-trial account was used during development. Live calls, access for callers outside the trial setup, and production number requirements have not been verified. No paid subscription or live number is configured.

**[View CodeMix VoiceDesk and its setup guide →](CodeMix-VoiceDesk/README.md)**

## QueryFence

QueryFence lets users ask questions about synthetic retail data in plain English. Users review the interpreted plan, adjust supported fields, inspect the SQL preview and cost estimate, and explicitly confirm before execution.

The planner proposes a structured query plan. Java validates the plan and compiles parameterized SQL. A dedicated PostgreSQL analytics account enforces read-only access.

Features include:

- Business metric definitions shared by the planner and interface
- Clarification of ambiguous questions and editable plan review
- Backend role policies and an independent SQL safety check
- Query cost estimates, persistent audit history, and summaries grounded in returned rows
- Versioned evaluations with measured results and baseline comparisons
- A scoped REST API, MCP adapter, embeddable widget, and client examples

Rules-based demo mode works without paid APIs or API keys. Local Ollama integration is optional.

All retail data is synthetic. This local portfolio demonstration does not establish production security or real-world accuracy. External platforms require compatible interfaces, permissions, and owner configuration.

**[View QueryFence and its setup guide →](QueryFence/README.md)**

## Getting started

```powershell
git clone https://github.com/vallemgopi/gopi-software-projects.git
cd gopi-software-projects
