# Wowza-Off-Air-Module
A module for wowza which allows you to automatically switch any stream to an off-air video loop if the source stream goes offline.

When this plugin is enabled each incoming stream will result in another stream being created with "_out" appended at the end. E.g. an incoming stream of "studio", would result in "studio_out" being created, and this is the stream the public should connect to.

If you don't want to build the module yourself simply copy the "Off-Air-Plugin.jar" file into the wowza "lib" directory and jump to the "configuration" section.

To use this first download eclipse and follow the instructions on http://www.wowza.com/streaming/developers

Then clone this, load the project into eclipse and make eclipse treat it as a wowza project somehow. The wowza eclipse addon should generate a build.xml file in the project directory and whenever you save it should build the lib and drop it in the correct wowza directory.

Configuration
--------
First go to the application you would like to use this mod for, and then register the module there. There is more info on how to do this at http://www.wowza.com/forums/content.php?625-How-to-get-started-as-a-Wowza-Streaming-Engine-Manager-administrator#configModules

The name and description can be set to whatever you want. The fully qualified class name is "uk.co.la1tv.offAirPlugin.OffAirPlugin".

Then you need to create the following application properties. Look at this for more info on how to do this: http://www.wowza.com/forums/content.php?625-How-to-get-started-as-a-Wowza-Streaming-Engine-Manager-administrator#configProperties

All properties should go on /Root/Application

- **la1OffAirPlugin-offAirVideo** (string) The name of the video that should be looped. It must be placed inside the wowza 'content' folder. E.g. "sample.mp4"
- **la1OffAirPlugin-timeToShowOffAirVideoFor** (integer) The time in seconds that the video should be shown for after an incoming stream finishes before the corresponding outgoing stream is ended. Defaults to 600
- **la1OffAirPlugin-streamsToRemainLive** (string) Comma seperated list of stream names (and application instance names) that you want to remain live. If you don't specify an application instance name it defaults to `__definst__`. Defaults to "". E.g. "stream1, stream2, stream3/AnotherAppInstance" Note: applications don't start on wowza startup so you will probably want to use http://www.wowza.com/forums/content.php?155-How-to-use-a-ServerListener-(IServerNotify)-to-load-and-lock-an-appInstance in conjunction with this.

If you are planning on using this or are having any issues please let me know!

Transcoder
----------
If you enable the wowza transcoder it should just work™, and only the output streams should get transcoded. E.g a stream of studio --> studio_out --> studio_out_360p and studio_out_480p etc

DVR
----------
If you enable the wowza dvr feature it should just work™, and only the output streams should have the dvr functionality enabled.
