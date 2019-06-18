# Demo project

This is a small demo to test the `aar-tool` leiningen plugin.

There is a stub library in Clojure (`my-library`) and a small Android project (`AARToolDemo`) using it.

If configured and launched correctly, the app should show a "Hello from Clojure!" string. 

It's not much to look at, but if you get to run it, you are actually executing a project depending from a clojure-based aar file where Clojure has access to the Android SDK.

## How to use the demo

*After* installing the aar-tool plugin (see the main `README.md`) and the android platform jar.

Enter the Clojure library and create the `aar` package:

    cd my-library
    ./lein aar-tool create-aar
    
This is probably the trickiest part: you need to have a properly set ANDROID_HOME variable, and the directory must contain android-19.jar in the appropriate directory, as well as the platform and build tools.
    
Once you have done this, you are almost there: copy the resulting aar in the android project.

    cp my-library.aar ../AARToolDemo
    
Compile the project and install it at leisure on your device:

    ./gradlew assembleDebug
    
If everything goes well, the main activity should present a blank activity with a "Hello from Clojure!" field in the center.

