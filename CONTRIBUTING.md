# Contribution workflow

For every tracked repository change, including documentation and benchmark fixtures:

1. Create a separate branch from the current `main`.
2. Commit and push the branch to the remote, then open a pull request targeting `main`.
3. Fix failures on that branch and push again until CI passes for the pull request's latest commit. CI must pass the Gradle build and report zero open issues in SonarCloud's full-project audit; a green new-code-only Sonar check is insufficient. Check both the workflow result and the full-project SonarCloud issue count before calling the change complete.
4. Leave the pull request unmerged for review unless the user authorizes merging it.

## External contributions and trusted analysis

Fork and Dependabot pull requests run the secret-free `validation` job. Their `build`
merge gate intentionally remains unsuccessful until a maintainer promotes a reviewed
revision to an internal pull request. Approving a fork workflow run or rerunning a
Dependabot workflow does not grant analysis secrets.

The maintainer must review the exact complete proposed tree before the trust transition,
including workflows, Gradle/buildSrc, the wrapper, dependencies, tests and executable tools.
Do not run fork-provided build code with repository secrets, automatically promote new
fork revisions, or replace this procedure with `pull_request_target` execution.

After review, promote the exact commit without cherry-picking or modifying its tree:

1. Record the external pull request's full head SHA as the reviewed revision.
2. Fetch that head and verify it still equals the reviewed SHA immediately before promotion.
3. Create a repository branch at that exact commit. Verify its SHA and tree against the
   reviewed source and check that the external PR has not advanced.
4. Open a maintainer-owned internal PR from the promoted branch. It runs the build and the
   trusted Sonar scan plus full-project zero-open-issues audit for its proposed head SHA.
5. Review every subsequent source change again before secret-bearing execution. Require
   the current internal PR's `build` check before merging; link the original fork PR to
   its replacement.

The read-only verification helper checks the supplied revision/tree identities. It does
not replace review. For example, after reviewing external PR123, run these commands from
a trusted checkout, replacing the reviewed SHA placeholder. Stop if any check fails:

```sh
pr=123
reviewed_sha='REPLACE_WITH_THE_REVIEWED_40_CHARACTER_SHA'
cmdshape git fetch origin "pull/${pr}/head"
source_sha=$(cmdshape git rev-parse FETCH_HEAD)
cmdshape test "$source_sha" = "$reviewed_sha"
reviewed_tree=$(cmdshape git rev-parse "${reviewed_sha}^{tree}")
source_sha=$(cmdshape gh api "repos/SuppieRK/Shoostr/pulls/${pr}" --jq .head.sha)
cmdshape test "$source_sha" = "$reviewed_sha"
cmdshape git push origin "${reviewed_sha}:refs/heads/reviewed/pr-${pr}"
promoted_sha=$(cmdshape gh api "repos/SuppieRK/Shoostr/git/ref/heads/reviewed/pr-${pr}" --jq .object.sha)
cmdshape git fetch origin "refs/heads/reviewed/pr-${pr}"
promoted_tree=$(cmdshape git rev-parse 'FETCH_HEAD^{tree}')
source_sha=$(cmdshape gh api "repos/SuppieRK/Shoostr/pulls/${pr}" --jq .head.sha)
REVIEWED_REVISION="$reviewed_sha" SOURCE_REVISION="$source_sha" \
PROMOTED_REVISION="$promoted_sha" REVIEWED_TREE="$reviewed_tree" \
PROMOTED_TREE="$promoted_tree" cmdshape node .github/ci/verify-promotion.mjs
cmdshape gh pr create --base main --head "reviewed/pr-${pr}"
```

The aggregate `build` job runs even if validation or analysis fails or is skipped. It
requires successful validation and completed analysis of the exact proposed revision.
Audit branches include the revision to prevent a newer PR update from replacing older
audit evidence. Main's ruleset requires `build` from GitHub Actions and an up-to-date
branch; the existing administrator bypass is retained.
