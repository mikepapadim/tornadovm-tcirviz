// tcirviz through JBang, without ./build.sh. The TC compiler comes from the TornadoVM SDK, so pass its jars:
//   jbang --cp "$TORNADOVM_HOME/share/java/tornado/*" tcirviz.java [File.java] [--port N] [--no-open]
//JAVA 21+
//COMPILE_OPTIONS --release 21 -proc:none
//RUNTIME_OPTIONS --enable-preview
//SOURCES src/main/java/io/github/mikepapadim/tcirviz/*.java
//SOURCES src/main/java/io/github/mikepapadim/tcirviz/**/*.java
//FILES tcirviz/web/=src/main/resources/tcirviz/web/*
//FILES tcirviz/examples/=src/main/resources/tcirviz/examples/*
//MAIN io.github.mikepapadim.tcirviz.TcIrViz
