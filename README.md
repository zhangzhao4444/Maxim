# Maxim 

> A Kotlin implementation of Monkey TEST(Non-Stub) for Android that runs on Simulator/Android devices. 

https://testerhome.com/topics/11719

## 1. Requirements

- Android 5£¬6£¬7£¬8
  - Android5 not support dfs

## 2. Setup

- adb push framework.jar /sdcard
- adb push monkey.jar /sdcard
[- adb push ape.strings /sdcard]
[- adb push awl.strings /sdcard]

## 3. Usage 

Maxim can be either started with adb command line. 

- adb shell CLASSPATH=/sdcard/monkey.jar:/sdcard/framework.jar exec app_process /system/bin tv.panda.test.monkey.Monkey -p com.panda.videoliveplatform --uiautomatordfs 5000

### 3.1 Args

[dfs mode]   --uiautomatordfs    monkey use DFS algorithm .  About 5 action per second.

[mix mode]  --uiautomatormix   monkey use AccessibilityService resolve tree node and random choose.  About 10-20 action per second.

--pct-uiautomatormix   uiautomator action ratio in mix mode

--running-minutes  n  monkey total run time

--act-whitelist-file /sdcard/awl.strings 
--act-blacklist-file 

other args is same to Android Monkey


