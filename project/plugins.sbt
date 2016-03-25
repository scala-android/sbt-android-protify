addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.20")

resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
   url("https://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
       Resolver.ivyStylePatterns)

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.1.2")

libraryDependencies <+= sbtVersion ("org.scala-sbt" % "scripted-plugin" % _)
