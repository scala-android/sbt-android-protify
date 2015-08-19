addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.4.9")

{
  val ver = System.getProperty("plugin.version")
  if (ver == null)
    throw new RuntimeException("""
      |The system property 'plugin.version' is not defined.
      |Specify this property using scriptedLaunchOpts -Dplugin.version."""
      .stripMargin)
  else addSbtPlugin("com.hanhuy.sbt" % "android-protify" % ver)
}

