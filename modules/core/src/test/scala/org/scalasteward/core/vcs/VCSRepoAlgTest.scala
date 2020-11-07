package org.scalasteward.core.vcs

import org.http4s.syntax.literals._
import org.scalasteward.core.git.Branch
import org.scalasteward.core.mock.MockContext.{config, gitAlg, vcsRepoAlg}
import org.scalasteward.core.mock.{MockContext, MockState}
import org.scalasteward.core.vcs.data.{Repo, RepoOut, UserOut}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class VCSRepoAlgTest extends AnyFunSuite with Matchers {
  val repo: Repo = Repo("fthomas", "datapackage")
  val repoDir: String = (config.workspace / "fthomas/datapackage").toString
  val askPass = s"GIT_ASKPASS=${config.gitAskPass}"
  val envVars = List(askPass, "VAR1=val1", "VAR2=val2")

  val parentRepoOut: RepoOut = RepoOut(
    "datapackage",
    UserOut("fthomas"),
    None,
    uri"https://github.com/fthomas/datapackage",
    Branch("master")
  )

  val forkRepoOut: RepoOut = RepoOut(
    "datapackage",
    UserOut("scalasteward"),
    Some(parentRepoOut),
    uri"https://github.com/scala-steward/datapackage",
    Branch("master")
  )

  test("clone") {
    val state = vcsRepoAlg.clone(repo, forkRepoOut).runS(MockState.empty).unsafeRunSync()
    state shouldBe MockState.empty.copy(
      commands = Vector(
        envVars ++ List(
          config.workspace.toString,
          "git",
          "clone",
          "--recursive",
          s"https://${config.vcsLogin}@github.com/scala-steward/datapackage",
          repoDir
        ),
        envVars ++ List(repoDir, "git", "config", "user.email", "bot@example.org"),
        envVars ++ List(repoDir, "git", "config", "user.name", "Bot Doe")
      )
    )
  }

  test("syncFork should throw an exception when doNotFork = false and there is no parent") {
    vcsRepoAlg
      .syncFork(repo, parentRepoOut)
      .runS(MockState.empty)
      .attempt
      .map(_.isLeft)
      .unsafeRunSync() shouldBe true
  }

  test("syncFork should sync when doNotFork = false and there is a parent") {
    val state = vcsRepoAlg.syncFork(repo, forkRepoOut).runS(MockState.empty).unsafeRunSync()
    state shouldBe MockState.empty.copy(
      commands = Vector(
        envVars ++ List(
          repoDir,
          "git",
          "remote",
          "add",
          "upstream",
          s"https://${config.vcsLogin}@github.com/fthomas/datapackage"
        ),
        envVars ++ List(repoDir, "git", "fetch", "--tags", "upstream", "master"),
        envVars ++ List(repoDir, "git", "checkout", "-B", "master", "--track", "upstream/master"),
        envVars ++ List(repoDir, "git", "merge", "upstream/master"),
        envVars ++ List(repoDir, "git", "push", "--force", "--set-upstream", "origin", "master")
      )
    )
  }

  test("syncFork should do nothing when doNotFork = true") {
    val config = MockContext.config.copy(doNotFork = true)
    val state = VCSRepoAlg
      .create(config)
      .syncFork(repo, parentRepoOut)
      .runS(MockState.empty)
      .unsafeRunSync()

    state shouldBe MockState.empty
  }
}
