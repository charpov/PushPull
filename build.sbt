val TinyScalaUtils = "com.github.charpov" %% "tiny-scala-utils" % "1.4.0"

ThisBuild / scalaVersion := "3.6.2"
ThisBuild / crossPaths   := false

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",     // Emit warning and location for usages of deprecated APIs.
  "-encoding:utf-8",  // Specify character encoding used by source files.
  "-feature",         // Emit warning for usages of features that should be imported explicitly.
  "-unchecked",       // Enable detailed unchecked (erasure) warnings.
  "-Wunused:imports", // check unused imports.
)

ThisBuild / scalacOptions += "-Yimports:java.lang,scala,scala.Predef,tinyscalautils.assertions"

ThisBuild / javacOptions ++= Seq("-deprecation", "-Xlint")

ThisBuild / resolvers += "TinyScalaUtils" at "https://charpov.github.io/TinyScalaUtils/maven/"

lazy val PushPull = (project in file(".")).settings(
  Compile / scalaSource := baseDirectory.value / "src" / "scala",
  Compile / javaSource  := baseDirectory.value / "src" / "java",
  libraryDependencies += TinyScalaUtils
)
