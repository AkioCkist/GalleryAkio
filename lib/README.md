# Third-party code

`cropper` and `gesture` are copied from [ImageToolbox](https://github.com/T8RIN/ImageToolbox)
(commit f78bc23), Apache License 2.0, see `LICENSE-ImageToolbox`. Copyright T8RIN (Malik Mukhametzyanov).

Changes made here:
- `cropper/widget` removed (sample UI, unused).
- `core:resources` dropped: `compositeOverSafe` replaced by `compositeOver`.
- ImageToolbox's EXIF library replaced by `androidx.exifinterface`.
- `ImageCropper` gained `onCropFraction`, reporting the crop instead of caching a file.

`:editor` also uses GPUImage (ImageToolboxLibs `gpuimage`) with the sharpen, vignette and
highlight/shadow filters set up as ImageToolbox's own filter classes do.
