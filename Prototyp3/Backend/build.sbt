name := "Estimator"

version := "0.1"

scalaVersion := "2.12.3"

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  "org.scalikejdbc" %% "scalikejdbc"  % "3.1.0",
  "org.postgresql" % "postgresql" % "9.4-1200-jdbc41",
  "com.twitter" %% "finatra-http" % "2.13.0"
)
