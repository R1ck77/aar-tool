# android_development

A Leiningen plugin to ease the development of Android .aar projects

## Usage

Put `[android_development "0.1.0-SNAPSHOT"]` into the `:plugins`
vector of your project.clj.

You will need to add some configuration values to the `project.clj`:

    :android-jar  specify the location of the "android.jar" file
    :aapt         path to the "aapt" command to use
	:aar-name     path to the destination .aar file

**TODO/FIXME** include the mechanism to find out the locations
automatically if not specified

also you need to specify

    :aot :all

in the options, and then you can create a aar from the code:

    $ lein android_development aar

which will trigger the compilation of the jar, first.

Watch for changes in the resources and update the `R.java` file as a
consequence:

    $ lein android_development update-R

## License

Copyright © 2016 Riccardo Di Meo

Proprietary license (temporary)
