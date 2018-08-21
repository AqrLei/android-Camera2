
Android Camera2 Sample
===================================

这个例子是基于[android-Camera2Raw][1]和[android-Camera2Video][2],通过[Kotlin][3]改写而成的，实现了预览、拍照
、录像、切换摄像头以及更换曝光模式等。

[1]: https://github.com/googlesamples/android-Camera2Raw
[2]: https://github.com/googlesamples/android-Camera2Video
Introduction
------------

 [Camera2 API][4]
 预览通过[TextureView][5]来实现，图片的保存使用了[ImageReader][6],录像则是用了[MediaRecorder][7]
[3]: http://kotlinlang.org/docs/reference/
[4]: https://developer.android.com/reference/android/hardware/camera2/package-summary.html
[5]: https://developer.android.google.cn/reference/android/view/TextureView
[6]: https://developer.android.google.cn/reference/android/media/ImageReader
[7]: https://developer.android.google.cn/reference/android/media/MediaRecorder

Pre-requisites
--------------

- Android SDK 21~27
- Kotlin v1.2.61
- Android Build Tools v27.0.3
- Android Support Repository v27.1.1

Screenshots
-------------

<img src="screenshots/main.png" height="400" alt="Screenshot"/>

Getting Started
---------------

This sample uses the Gradle build system. To build this project, use the
"gradlew build" command or use "Import Project" in Android Studio.

Support
-------

- Google+ Community: https://plus.google.com/communities/105153134372062985968
- Stack Overflow: http://stackoverflow.com/questions/tagged/android

If you've found an error in this sample, please file an issue:
https://github.com/AqrLei/android-Camera2

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub. Please see CONTRIBUTING.md for more details.

License
-------

Copyright 2017 The Android Open Source Project, Inc.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.
