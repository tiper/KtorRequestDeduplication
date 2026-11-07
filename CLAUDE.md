# Working rules

## Finishing a pull request

This is standing permission. Don't ask for confirmation at each step.

1. Open the PR.
2. Address or push back on every Copilot comment, then close the threads. Copilot also raises issues in its review summary, so read that as well as the inline threads.
3. Verify each comment before changing code, ideally with a test that fails first. Push back with evidence when it's wrong.
4. Push the changes, and repeat until a push draws no new comments.
5. Once CI is green, check the PR's comments and reviews again. Merge only if there are no new ones.
6. Squash and merge by hand, with a simple, assertive commit message.

Never use GitHub auto-merge: it would skip the final comment check in step 5.

Never bypass branch protection. A human reviewer's comments are for the maintainer to weigh in on, so raise them first.
