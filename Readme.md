# imagine - An image engine for your web app

`imagine` is an **imag**e eng**ine** for your web apps - you can use it as a Ring
middleware to resize, crop, and filter your images on the run, or export images
to disk.

`imagine` produces unique paths to images based on their content and processing
chain content, making it ideal for serving with a far future expires header,
ideal for client-side caching and a high-performant site.

## Using with Ring

`imagine` gives you a Ring middleware to

```clj
(require '[imagine.core :as imgeng])

(def image-asset-config
  {:url-prefix "images"
   :resource-path "photos"
   :transformations
   {:small-circle [[:crop {:width 200 :height 200}]
                   [:circle]]
    :red-green [[:duotone {:from [255 0 0] :to [0 255 0]}]]
    :bw-thumb [[:resize {:width 90}]
               [:grayscale]]}})

(def handler
  (-> app
      (imgeng/wrap-images image-asset-config)))
```

With the middleware in place, any request to `/images/*` will be handled by the
image engine. Specifically, a URL like `/images/red-green/ab6ab67c/gal.jpg` will
serve up the resource `photos/gal.jpg` with a red-to-green duotone filter.

A link to this image can be created with:

```clj
(imgeng/url image-asset-config :red-green "/photos/gal.jpg")
```

## Using with static sites

If you ship a bre-built static site to production, e.g. built with
[Stasis](https://github.com/magnars/stasis), `imagine` can generate files to
disk for you. Given a list of images to generate, and a directory to put them
in, the following snippet will provide you with all the images you need:

```clj
(require '[imagine.core :as imagine])

(imagine/export-images image-asset-config dir images)
```

Even better, `imagine` can find the images to generate for you. Given a
directory of static HTML files, it can look through them, find all the image
tags, filter out the relevant image links and generate them all to disk for you:

```clj
(require '[imagine.core :as imagine])

(let [images (imagine/find-images image-asset-config "path/to/static/site")]
  (imagine/export-images image-asset-config dir images))
```

## Configuration

`imagine` relies on an image asset configuration to do its job. It can contain
the following keys:

- `:url-prefix` - When using the Ring middleware, any URL starting with this
  string will be handled by `imagine`. The prefix should not include a leading
  slash.
- `:resource-path` - The prefix path in the resources under which to look for
  images. An image URL of `/<prefix>/<style>/<content-hash>/path.ext` will look
  for a resource `<resource-path>/<path>.<ext>`.
- `:transformations` - The available transformations. This is a map of
  transformation name to a vector of transformations to apply. Each
  transformation is itself a vector.

## Transformations

Transformation configurations can include multiple transformations - even the
same transformation multiple times. Think of them as a pipeline to perform in
order. A transformation configuration is a vector of `[transformation-keyword
params]`.

### Resize

Resizes the image. Accepts a hash of options as its only argument:

- `:width` - The width to resize to. If not set, aspect ratio will be
  maintained, and `:width` is calculated from `:height`
- `:height` - The height to resize to. If not set, aspect ratio will be
  maintained, and `:height` is calculated from `:width`

If neither `:width` nor `:height` is set, an exception is thrown.

```clj
[:resize {:width 200}]
```

### Crop

Crop an image. Takes a hash of options:

```clj
[:crop {:width 200}]
```

Options:

- `:width` - The width to crop to, as an integer number of pixels. If not set,
  aspect ratio will be maintained, and `:width` is calculated from `:height`
- `:height` - The height to crop to, as an integer number of pixels. If not set,
  aspect ratio will be maintained, and `:height` is calculated from `:height`.
- `:preset` - Can be set to `:square` to crop the image to, well, a square. The
  size of the image will be the smallest of width/height.
- `:offset-x` - The horizontal cropping offset. Set to a number to indicate
  pixel position, or optionally `:left`, `:center` or `:right` to anchor the
  crop. If not set, `imagine` will attempt to crop to the center of the image.
- `:offset-y` - The vertical cropping offset. Set to a number to indicate pixel
  position, or optionally `:top`, `:center` or `:bottom` to anchor the crop. If
  not set, `imagine` will attempt to crop to the center of the image.

### Triangle

Cut a triangle from the image, leaving the rest transparent. Forces the output
to be a PNG. Takes the anchoring position as its single argument, one of:

- `:top-left`
- `:bottom-left`
- `:bottom-right`
- `:top-right`

```clj
[:triangle :top-left]
```

### Circle

Cut a circle from the image, leaving the rest transparent. Forces the output to
be a PNG. Takes an optional argument anchoring the circle. The default is to
center the circle. The output image will always be square. The diameter of the
circle will be the smalles of the width/height of the image. The optional
position can be one of:

- `:top`
- `:center`
- `:left`
- `:right`
- `:bottom`

```clj
[:circle] ;; Cut a circle from the center
[:circle :top]
```

Important note! If combining this effect with other transparency-based croppers
(e.g. `:triangle`), the circle must be cut first. This is an artifact of its
current implementation, which does not compose well, but does produce a nicely
anti-aliased circle cutout...

### Grayscale

Turns the image grayscale.

```clj
[:grayscale]
```

### Duotone

Converts the image to duotone, which is like grayscale, except instead of
mapping each pixel to tone along the black-white scale, you set the from and to
color yourself. Colors are expressed as a vectors of R G B, and the arguments to
the filter is the from color, then the to color:

```clj
[:duotone [255 0 0] [0 255 0]]
```

Produces a duotone image from red to green.

### Rotate

Rotates the image one of `90`, `180` or `270` degrees.

```clj
[:rotate 90]
```

## API

### `(imagine.core/wrap-images handler config)`

Ring middleware.

### `(imagine.core/image-url? url config)`

Given the image asset config in `config`, return `true` if this URL path is an
image path that imagine can process.

### `(imagine.core/image-spec url)`

Returns a map with information about the image URL. The map contains the
following keys:

- `:transformation` - The name of the transformation config to apply
- `:filename` - The image filename
- `:ext` - The extension
- `:url` - The input URL path

### `(imagine.core/inflate-spec spec config)`

Given a spec as returned from `image-spec`, return a fully inflated spec. This
includes a resource pointing to the underlying image file. Regardless of the
extension in the URL path, the file on disk may be either a JPG or a PNG. If
both exist on disk, `imagine` throws an error.

The returned map contains the keys.

- `transformation` - As above
- `ext` - As above
- `:resource` - A resource object pointing to the file on disk
- `:cache-path` - A temporary path on the file system where a transformed image
  will be cached by the Ring handler

### `(imagine.core/realize-url config url)`

Given a non-prefixed URL, like `/round/some/file.jpg`, and the image asset
configuration, returns a fully resolvable URL that includes the URL prefix, a
content hash, and the appropriate extension, e.g.:
`/image-assets/round/b234c32/some/file.png`.

### `(imagine.core/url-to config transformation file)`

Generates a URL to a file with a given transformation:

```clj
(require '[imagine.core :as imagine])

(def image-asset-config
  {:prefix "image-assets"
   :transformations
     {:round [[:circle]]}})

(imagine/url-to image-asset-config :round "/some/file.jpg")
;;=> /image-assets/round/b234c32/some/file.png
```

### `(imagine.core/transform-image config out-path)`

Given a full `imagine` URL path, like
`/image-assets/round/b234c32/some/file.png`, transforms the image and outputs it
to the provided out directory at
`<out-path>/image-assets/round/b234c32/some/file.png`. Creates all necessary
diretories.

## License

Copyright © 2019 Christian Johansen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
