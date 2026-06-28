# java-upgrader — Project Guardrails

## Code Change Rules

1. **Unit tests required before any code change is complete.**
   After modifying any `.java` file, write or update a unit test that covers the changed code and confirm it passes (`mvn test -pl . -Dtest=<TestClass>`) before considering the task done. This applies to major refactoring as well — run the full `mvn test` after any structural change (new classes, refactored service boundaries, changed constructors) before reporting the task as done.

2. **Update README after refactoring if needed.**
   After any refactoring, check whether the README needs updating before considering the task done. Update it if any of the following changed: MCP tool names or parameters, tool return values, project structure, configuration properties, or the demo client code.

3. **Run `mvn clean install` after major changes.**
   "Major" means: adding/removing dependencies, changing the agent's tool contracts, refactoring across multiple classes, or any structural change. A single-method tweak does not require a full build, but when in doubt, run it.

4. **Never push to the remote GitHub repository without explicit user permission.**
   Do not run `git push` (or any variant: `--force`, `-u`, etc.) unless the user explicitly says to push in that turn. Creating commits locally is fine; pushing is not.

5. **Security review gate is enforced by a pre-commit git hook.**
   `.githooks/pre-commit` blocks any commit unless the `code-security-reviewer` agent has approved the current staged changes. The agent writes `.security-review` (gitignored) stamped with the staged-tree hash; the hook verifies the hash still matches at commit time. Re-run the reviewer any time you stage new changes after a review. Emergency bypass only: `git commit --no-verify`.

6. **Secret scanning is enforced by a pre-push git hook.**
   `.githooks/pre-push` blocks any push that contains GitHub tokens, AWS keys, Anthropic keys, PEM private keys, or `keyword=value` credential assignments in the outgoing diff. Both hooks are installed automatically on every `mvn initialize` (or `mvn install`). After a fresh `git init`, run `mvn initialize` once to activate them. Emergency bypass only: `git push --no-verify`.
