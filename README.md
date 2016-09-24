# android_development

A Leiningen plugin to ease the development of Android .aar projects

## Usage

Put `[android_development "0.1.0-SNAPSHOT"]` into the `:plugins`
vector of your project.clj.

Create a aar from the code:

    $ lein android_development aar

(it will trigger the compilation of a jar).

Watch for changes in the resources and update the `R.java` file as a
consequence:

    $ lein android_development update-R

## License

Copyright © 2016 Riccardo Di Meo

Proprietary license (temporary)
