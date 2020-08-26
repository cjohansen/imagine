# imagine - An image engine for your web app

`imagine` is an **imag**e eng**ine** for your web apps - you can use it as a Ring
middleware to resize, crop, and filter your images on the run, or export images
to disk.

`imagine` produces unique paths to images based on their content and processing
chain content, making it ideal for serving with a far future expires header,
ideal for client-side caching and a high-performant site.

Install with tools.deps:

```clj
cjohansen/imagine {:mvn/version "2020.08.25"}
```

Install with Leiningen:

```clj
[cjohansen/imagine "2020.08.25"]
```

## Using with Ring

`imagine` gives you a Ring middleware to

```clj
(require '[imagine.core :as imagine])

(def image-asset-config
  {:url-prefix "images"
   :resource-path "photos"
   :transformations
   {:small-circle [[:crop {:width 200 :height 200}]
                   [:circle]]

    :red-green    [[:duotone {:from [255 0 0] :to [0 255 0]}]]

    :bw-thumb     [[:resize {:width 90}]
                   [:grayscale]]}})

(def handler
  (-> app
      (imagine/wrap-images image-asset-config)))
```

With the middleware in place, any request to `/images/*` will be handled by the
image engine. Specifically, a URL like `/images/red-green/ab6ab67c/bird.jpg` will
serve up the resource `photos/bird.jpg` with a red-to-green duotone filter.

A link to this image can be created with:

```clj
(imagine/url image-asset-config :red-green "/photos/bird.jpg")
```

## Using with static sites

If you ship a bre-built static site to production, e.g. built with
[Stasis](https://github.com/magnars/stasis), `imagine` can generate files to
disk for you. Given a list of images to generate, and a directory to put them
in, the following snippet will provide you with all the images you need:

```clj
(require '[imagine.core :as imagine])

(def dir "build")

(doseq [image images]
  (-> image
      imagine/image-spec
      (imagine/inflate-spec image-asset-config)
      (imagine/transform-image-to-file (str dir image))))
```

## Configuration

`imagine` relies on an image asset configuration to do its job. It can contain
the following keys:

- `:prefix` - When using the Ring middleware, any URL starting with this string
  will be handled by `imagine`. The prefix should not include a leading slash.
  This prefix cannot be empty. If omitted, it defaults to `"image-assets"`.
- `:resource-path` - The prefix path in the resources under which to look for
  images. An image URL of `/<prefix>/<style>/<content-hash>/path.ext` will look
  for a resource `<resource-path>/<path>.<ext>`.
- `:transformations` - The available transformations. This is a map of
  transformation name to a map describing the transformation. [See
  below](#transformation-config) for details.
- `:use-cacheable-urls?` - When set to `true`, imagine will compute the hash
  part of the URL using the contents of the image file along with the
  transformation config. This is slower, because the image must be read into
  memory, even if a transformed version is already cached on disk, but it means
  you can serve the image with a far future expires header - effectively caching
  "forever" on the client. The default is `false`, and the value should be
  `true` in production.
- `:disk-cache?` - When set to `true`, transformed images will be cached
  temporarily on disk. Subsequent requests to the same image/transformation
  combination will serve the cached file on disk. **Note:** If
  `:use-cacheable-urls?` is `false`, and the image file has changed since it was
  last served, this will cause imagine to serve a stale image.
- `:tmpdir` - The directory to cache transformed images in. Defaults to the
  system `java.io.tmpdir`.

### Transformation config

The transformation map can contain these keys:

- `:transformations` - A vector of transformations to apply. Each transformation
  is itself a vector of `[:name & args]`, [see below](#transformations).
- `:width` - The width of the output image.
- `:height` - The height of the output image.
- `:quality` - The JPG quality setting, from 0 to 1.
- `:progressive?` - Whether the JPG should be progressive.
- `:retina-optimized?` - Encodes the recommendation from [this article on retina
  images](https://alidark.com/responsive-retina-image-mobile/) - double the
  image size and lower the quality setting to `0.3`. Only applies to JPGs.
- `:retina-quality` - Optionally override the default retina quality of `0.3`.

If neither `:width` nor `:height` is set, the output image is not resized. If
either one is missing, the other is computed to maintain aspect ratio. It is
considered an error to set `:retina-optimized?` to `true` when there is no
explicit `:width` or `:height`.

## Transformations

Transformation configurations can include multiple transformations - even the
same transformation multiple times. Think of them as a pipeline to perform in
order. A transformation configuration is a vector of `[transformation-keyword
params]`.

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
- `:origin` - Set to a vector of keywords, specifying the x and y origin of the
  crop, use one of `:left`, `:center`, `:right` with one of `:top`, `:center`,
  `:bottom`, e.g.: `[:left :center]`, to cut out superfluous pixels from the
  top, bottom, and right of the image.
- `:origin-x` - A keyword specifying only the horizontal anchoring point.
- `:origin-y` - A keyword specifying only the vertical anchoring point.
- `:allow-smaller?` - If the crop dimensions are larger than the actual size of
  the image, is it ok to end up with an image smaller than the crop dimensions?
  Defaults to `true`, set to `false` to have Imagine throw an exception when
  attempting to crop an image in a way that exceeds its dimensions.

`:offset-x` and `:offset-y` are supported for backwards compatibility, but
should be avoided in favor of the `:origin` options. Both of these can take
either a number, or one of the `:origin` keywords.

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

### Fit

Fits the image within a frame, maintaining aspect ratio, cropping the image if
necessary. By default does not scale images up, so if the image is too small to
fit, it will at least not overflow the specified dimensions.

The fit filter works by first scaling the image down to fit the shorter side in
the specified frame. It is then cropped, if necessary to fit in the other
direction. The default is to crop to the center, but this behavior can be
influenced if desired.

```clj
[:fit {:width 400 :height 200}]
```

If this transformation is applied to an image that is 1000x800 pixels:

- It is first scaled down to 400x320
- It is then cropped vertically with a Y offset of 60px

Options:

- `:width` - The desired width of the image
- `:height` - The desired height of the image
- `:offset-y` - Any of the values supported by `:crop`, default `:center`
- `:offset-x` - Any of the values supported by `:crop`, default `:center`
- `:scale-up?` - If set to `true`, allow the image to be scaled up, if necessary

### Resize

Resizes the image. Only use this transform if you need to resize the image
before applying other transforms. Use `:width` and/or `:height` on the
transformation map to control output size. Accepts a hash of options as its only
argument:

- `:width` - The width to resize to. If not set, aspect ratio will be
  maintained, and `:width` is calculated from `:height`
- `:height` - The height to resize to. If not set, aspect ratio will be
  maintained, and `:height` is calculated from `:width`
- `:smallest` - Maintains aspect ratio and resizes the image so that the
  smallest dimension (either `:width` or `:height`) measures this many pixels.

If neither `:width` nor `:height` is set, an exception is thrown.

```clj
[:resize {:width 200}]
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

Copyright © 2019-2020 Christian Johansen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
