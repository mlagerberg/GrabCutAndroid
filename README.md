# GrabCutAndroid

A dummy app that demonstrates foreground-background segmentation using the GrabCut algorithm in Android using OpenCV.

**This is a work in progress that attempts to use a polygon outline as a mask instead of only a rectangle, for increased accuracy. For a working version that uses only a rectangle, check [the original](https://github.com/mrmitew/GrabCutAndroid).**

## To do:

- [ ] Offset the drawn polygon to 0,0 and crop the source image
- [ ] Use findContours to pick the largest contour in the mask
- [ ] Perform poor man's anti-aliasing by rendering the contour on a 4x upscaled mask and resize down the cut image. 

## Project dependencies:
- Kotlin 1.51.1
- OpenCV 3.3.1
- RxJava 2.1.6
- RxKotlin 2.1.0
- RxAndroid 2.0.1
- RxPermissions 0.9.4
