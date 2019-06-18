# aar-tool

A Leiningen plugin to create Android `aar` files from Clojure projects.

This plugin was created for my personal use. In particular, I need it
for my app
[VR Theater](https://play.google.com/store/apps/details?id=it.couchgames.apps.cardboardcinema) and,
despite how basic (and bugged) it may be, it seems to be doing its
job.

Before you start, a friendly advice.

Google is happily drinking the Kool-Aid from Idea with Kotlin, and for
various reasons the Clojure community is less than luke-warm about
mobile development, so if you decide to develop Clojure on Android
_you are on your own_.

Also, this is a one of my earliest Clojure projects, it was *developed
and tested on Linux* (it _should_ work on Windows and Mac too,
though…) and it was hastly crammed together for my personal use so
keep your expectations low.

## Limitations

Even when working at its best, `aar-tool` cannot protect the adventurous developer from the (too) many kinks of Android-Clojure development.

* Unless you are using the excellent (and unfortunately unmaintained) [Alexander Yakushev's version of Clojure](https://github.com/clojure-android/lein-droid/wiki/Tutorial) forget about REPL-based development on the device (which was super-cool!)
* Android SDK functions cannot be invoked when doing REPL development on your machine
* My app on the store uses Clojure 1.8.0, but I have experienced some annoying issues with dynamic compilation on newer versions of Clojure
* You will be forced to use multiDexing by default
* You will probably need to tweak ProGuard
* The last time I checked, the gradle plugin for Android required all `aar` files to be present even before invoking a task (which makes automating the compilation annoying)
* The feeling of loneliness when you encounter a Clojure/Android issue is soul-crushing.

So consider very carefully if you really want to embark in this endeavor, but know that if your stakes aren't high, you can still have fun.

## Requirements 

`aar-tool` directly invokes a number of android command line tools, so make sure to have the build tools and platform tools installed, and an ANDROID_HOME variable properly set.

At the time of writing, the plugin is working with the Android SDK Build-Tools and Platform-Tools 29, and Android SDK Tools at version 26.1.1.

In order to run the demo you will also need the "android platform 19" jar (that is, "Android 4.4. (KitKat)" on Idea) locally installed. You'll probably use the `mvn` command for that.

After installing this plugin with `lein install`, the best way to check that your requirements are met is to compile the `my-library` demo by entering the leiningen project and executing:

    lein aar-tool

If you can do that and get a `aar` file in the project folder, you are practically already there.

## Install aar-tool

Clone the repository and run `lein install`.

## Configuring a leiningen project for aar-tool

Enable the `aar-tool` plugin by adding the following line:

    :plugins [[aar-tool "0.3.0"]]           ;;; use aar-tool

to the `project.clj` file.

The following lines are `aar-tool`-specific:

    :aar-name "library_name.aar"            ;;; output file 
    :res "res"                              ;;; resources location
    :android-manifest "AndroidManifest.xml" ;;; the Android manifest to use for the project
    
`aar-tool` will not modify the manifest, but will look into it for the
package and the Sdk versions.

This is especially important if you planning to use the Android Sdk
with the Clojure library.

Due to how the plugin works, you need at least a `java-source-path` set:

    :java-source-paths ["java-src"]

(create the `java-src` dir) in order to save the `R.java` class.

Due to a bug, you currenly need at least a resource in `res`, so if you don't plan to have any, create a file `res/values/strings.xml` with the following content:

    <?xml version="1.0" encoding="utf-8"?>
    <resources>
      <string name="placeholder">aar-tool is very rough around the edges</string>
    </resources>

to make `aar-tool` happy.

Other `project.clj` options are mandatory to create a valid Android `aar` file:

    :aot :all
    :omit-source true
    :javac-options ["-target" "1.7" "-source" "1.7"]

`:aot :all` is definitely required, while for the other options there may be some cargo cult programming involved :p (really: I don't remember why I used them).

With this setup, `aar-tool` works and you can create Android applications using Clojure, but if you want to access the Android SDK with Clojure, you need to make the correct android JAR available in some repository.

### Make the android platform available for Leiningen

If you want your library to use Android classes (and the resources!) you need something like this:

    :dependencies [[org.clojure/clojure "1.9.0"]
                   [android/android "19.0.0"]]

in your `project.clj` file. 

Unless there is a Maven repository I ignore, where such android JAR can be found, you'll probably need to install the JAR in your local repository yourself:

    mvn install:install-file -Dfile=${ANDROID_HOME}/platforms/android-19/android.jar -DartifactId=android -Dversion=19.0.0 -DgroupId=android -Dpackaging=jar

Keep in mind that due to how Android applications are compiled, you are actually installing a library full of stubs, not the code actually used in your application.

Also, this step is required only once for each android platform version you plan to use with `aar-tool`.

### Using the generated AAR files

After invoking 

    $ lein aar-tool aar

you'll be hopefully rewarded with a aar file you can add to your Android project.

The aar file doesn't bring Clojure in, so you will need to add the correct version of Clojure as a dependecy for your Android project.

For an example about how to use the `flatDir` and `dependencies` commands, check the `build.gradle` scripts in the `AARToolDemo` project.

## Bugs and limitations

Many, of both.

* changing the package in the `AndroidManifest.xml` of the plugin requires one to clear the java sources manually from stale `R.java` files
* the plugin uses the last versions of the build and platform tools (builds are not repeatable)
* as already mentioned, at least one resource must be present for the plugin to work
* the error messages are not very descriptive
* matching the correct min/max/targetSdkVersions and clojure versions 

## License

Copyright © 2019 Riccardo Di Meo

This work is distributed under the terms of the Creative Commons Attribution 4.0 International Public License.

The full text of the license can be found at the URL https://creativecommons.org/licenses/by/4.0/legalcode


