inThisBuild(Seq(
  organization := "com.github.cornerman",

  scalaVersion := "2.12.15",
  crossScalaVersions := Seq("2.12.15", "2.13.7"),

  licenses := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),

  homepage := Some(url("https://github.com/cornerman/mycelium")),

  scmInfo := Some(ScmInfo(
    url("https://github.com/cornerman/mycelium"),
    "scm:git:git@github.com:cornerman/mycelium.git",
    Some("scm:git:git@github.com:cornerman/mycelium.git"))
  ),

  pomExtra :=
    <developers>
      <developer>
        <id>jkaroff</id>
        <name>Johannes Karoff</name>
        <url>https://github.com/cornerman</url>
      </developer>
    </developers>
))

lazy val commonSettings = Seq(

  libraryDependencies ++=
    Deps.boopickle.value % Test ::
    Deps.scalaTest.value % Test ::
    Nil,

  scalacOptions ++=
    "-encoding" :: "UTF-8" ::
    "-unchecked" ::
    "-deprecation" ::
    "-explaintypes" ::
    "-feature" ::
    "-language:_" ::
    "-Xfuture" ::
    "-Xlint" ::
    "-Ywarn-value-discard" ::
    "-Ywarn-extra-implicit" ::
    "-Ywarn-unused" ::
    Nil,

  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) =>
        "-Ywarn-nullary-override" ::
        "-Ywarn-nullary-unit" ::
        "-Ywarn-infer-any" ::
        "-Yno-adapted-args" ::
        "-Ypartial-unification" ::
        Nil
      case _ =>
        Nil
    }
  }
)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "mycelium-core",
    libraryDependencies ++=
      Deps.scribe.value ::
      Deps.chameleon.value ::
      Nil
  )

lazy val clientJS = project
  .in(file("client-js"))
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .dependsOn(core.js)
  .settings(commonSettings)
  .settings(
    name := "mycelium-client-js",
    npmDependencies in Compile ++=
      "reconnecting-websocket" -> "4.1.10" ::
      Nil,
    npmDependencies in Test ++=
      "html5-websocket" -> "2.0.1" ::
      Nil,
    libraryDependencies ++=
      Deps.scalajs.dom.value ::
      Nil
  )

lazy val akka = project
  .in(file("akka"))
  .dependsOn(core.jvm)
  .settings(commonSettings)
  .settings(
    name := "mycelium-akka",
    libraryDependencies ++=
      Deps.akka.http.value ::
      Deps.akka.actor.value ::
      Deps.akka.stream.value ::
      Deps.akka.testkit.value % Test ::
      Nil,
  )

lazy val root = project
  .in(file("."))
  .settings(
    name := "mycelium-root",
    skip in publish := true,
  )
  .aggregate(core.js, core.jvm, clientJS, akka)
