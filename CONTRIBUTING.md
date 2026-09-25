# Contribution workflow

For every tracked repository change:

1. Create a separate branch from the current `main`.
2. Commit and push the branch to the remote, then open a pull request targeting `main`.
3. Fix failures on that branch and push again until CI passes for the pull request's latest commit. CI must pass the Gradle build and report zero open issues in SonarCloud's full-project audit; a green new-code-only Sonar check is insufficient.
4. Leave the pull request unmerged for review unless the user authorizes merging it.
