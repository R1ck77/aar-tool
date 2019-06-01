# aar-tool

A Leiningen plugin to ease the development of mixed Clojure/Android projects.

It can be used to create .aar files from leningen projects to be included as external libraries into Android projects.

As of now, the tool only creates library .aar but could be probably extended easily to create whole applications.

This is a one of my earliest Clojure projects, and it was hastly crammed together for my personal use so keep any expectation low, especially as far as ease of use is concerned.

## Requirements 

*TODO*

## Configuration

Add `aar-tool` as a dependency by adding `[aar-tool "0.1.0-SNAPSHOT"]` to your project's `:plugins` in `project.clj`.

You will need to add some configuration values to the `project.clj`:

	:aar-name     path to the destination .aar file
    :res          location of the android "res" directory

also you need to specify

    :aot :all

in the options for the AAR generation (not needed for the other
sub-tasks).

### Create AAR library

You can create a aar from the code:

    $ lein aar-tool aar

which will trigger the compilation of the jar, first.

The location of the Android Sdk is extracted from the ANDROID_HOME
environment variable (which must be specified).

The `aapt` to be used is the one in the newest version of the
installed `build-tools` (**Warning** this behavior promotes
non-repeatable behavior between different installations!).

### Create a `R.java` file

Run:

    $ lein aar-tool create-R
    
to create an appropriate `R.java` file in the `src` directory.

### Create the `R.java` file automatically

Watch for changes in the `:res` directory, and update the `R.java`
file as a consequence:

    $ lein aar-tool update-R

## License

Copyright © 2016 Riccardo Di Meo

Proprietary license (temporary)
