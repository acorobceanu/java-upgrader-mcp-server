# java-upgrader — Project Guardrails

## Code Change Rules

1. **Unit tests required before any code change is complete.**
   After modifying any `.java` file, write or update a unit test that covers the changed code and confirm it passes (`mvn test -pl . -Dtest=<TestClass>`) before considering the task done.

2. **Run `mvn clean install` after major changes.**
   "Major" means: adding/removing dependencies, changing the agent's tool contracts, refactoring across multiple classes, or any structural change. A single-method tweak does not require a full build, but when in doubt, run it.

3. **Never push to the remote GitHub repository without explicit user permission.**
   Do not run `git push` (or any variant: `--force`, `-u`, etc.) unless the user explicitly says to push in that turn. Creating commits locally is fine; pushing is not.

4. **Secret scanning is enforced by a pre-push git hook.**
   `.githooks/pre-push` blocks any push that contains GitHub tokens, AWS keys, Anthropic keys, PEM private keys, or `keyword=value` credential assignments in the outgoing diff. The hook is installed automatically on every `mvn initialize` (or `mvn install`). After a fresh `git init`, run `mvn initialize` once to activate it. Emergency bypass only: `git push --no-verify`.
