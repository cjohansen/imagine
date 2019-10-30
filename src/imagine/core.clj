(ns imagine.core
  (:require [fivetonine.collage.util :as util]
            [fivetonine.collage.core :as collage]
            [imagine.digest :as digest]
            [clojure.java.io :as io])
  (:import java.io.File
           java.awt.image.BufferedImage
           java.awt.image.ColorConvertOp
           java.awt.color.ColorSpace))

(def formatter
  (doto (java.text.SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss zzz" java.util.Locale/US)
    (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))))

(defn last-modified [^File file]
  (.format formatter (-> (.lastModified file)
                         (/ 1000) (long) (* 1000)
                         (java.util.Date.))))

(def default-tmpdir (System/getProperty "java.io.tmpdir"))

(defn- create-folders [path]
  (.mkdirs (.getParentFile (io/file path))))

(defn- cache-path [config spec]
  (str (or (:tmpdir config) default-tmpdir)
       (digest/sha-1 (pr-str (if (:cacheable-urls? config)
                               (update spec :resource slurp)
                               (update spec :resource #(.getPath %)))))
       "."
       (name (:ext spec))))

(defmulti transform (fn [transformation image & args] transformation))

(defn- resolve-offset [opt k image-dim crop-k [start center end]]
  (let [offset (opt k)
        crop-dim (opt crop-k)]
    (dissoc
     (cond
       (number? offset) opt
       (or (= start offset) (nil? offset)) (assoc opt k 0)
       (= center offset) (assoc opt k (int (/ (- image-dim crop-dim) 2)))
       (= end offset) (assoc opt k (- image-dim crop-dim)))
     :preset)))

(defn- resolve-crop-presets [opt width height]
  (if (= :square (:preset opt))
    (let [dim (min width height)]
      (merge opt {:width dim
                  :height dim
                  :offset-x (or (:offset-x opt) :center)
                  :offset-y (or (:offset-y opt) :center)}))
    opt))

(defn- resolve-dimensions [opt w h]
  (cond
    (nil? (:width opt))
    (assoc opt :width (int (* (/ w h) (:height opt))))

    (nil? (:height opt))
    (assoc opt :height (int (* (/ h w) (:width opt))))

    :default opt))

(defn crop-params [^BufferedImage image {:keys [width height offset-x offset-y] :as opt}]
  (let [w (.getWidth image)
        h (.getHeight image)]
    (-> opt
        (resolve-crop-presets w h)
        (resolve-dimensions w h)
        (resolve-offset :offset-x w :width [:left :center :right])
        (resolve-offset :offset-y h :height [:top :center :bottom]))))

(defn- crop [image {:keys [width height offset-x offset-y]}]
  (collage/crop image offset-x offset-y width height))

(defmethod transform :crop [_ image opts]
  (when-not (map? opts)
    (if (= :square opts)
      (throw (Exception. (format "Crop expects a map of options. To square an image, do [:crop {:preset :square}]")))
      (throw (Exception. (format "Crop expects a map of options {:preset :width :height :offset-x :offset-y}")))))
  (crop image (crop-params image opts)))

(defmethod transform :triangle [_ image position]
  (when-not (keyword? position)
    (throw (Exception. (format "Triangle expects a keyword position: [:triangle :lower-left]. Choose from #{:lower-left :lower-right :upper-left :upper-right}"))))
  (collage/triangle image {:position position}))

(defmethod transform :circle [_ image & [position]]
  (when position
    (throw (Exception. "Circle does not yet implement its position argument")))
  ;; position not yet implemented
  (collage/circle image))

(defmethod transform :grayscale [_ image]
  (collage/grayscale image))

(defn ensure-rgb [^BufferedImage image]
  (let [output (BufferedImage. (.getWidth image) (.getHeight image) BufferedImage/TYPE_INT_RGB)]
    (-> (ColorConvertOp. (.. image getColorModel getColorSpace) (ColorSpace/getInstance ColorSpace/CS_sRGB) nil)
        (.filter image output))
    output))

(defmethod transform :duotone [_ image from-color to-color]
  (-> image
      ensure-rgb
      (collage/duotone from-color to-color)))

(defmethod transform :rotate [_ image theta]
  (collage/rotate image theta))

(defn resize-params [^BufferedImage image opt]
  (if (:smallest opt)
    (let [w (.getWidth image)
          h (.getHeight image)]
      (if (< w h)
        {:width (:smallest opt)
         :height (int (* (/ h w) (:smallest opt)))}
        {:height (:smallest opt)
         :width (int (* (/ w h) (:smallest opt)))}))
    opt))

(defn- resize [image {:keys [width height]}]
  (collage/resize image :width width :height height))

(defmethod transform :resize [_ image opt]
  (resize image (resize-params image opt)))

(defn- fit-resize-params-1 [img-ratio frame-ratio width height]
  (cond
    ;; Aspect ratio unchanged
    (= img-ratio frame-ratio)
    {:width width
     :height height}

    ;; Square images
    (= 1 img-ratio)
    (let [dim (max width height)]
      {:width dim :height dim})

    ;; Wide image, wide frame
    (and (< 1 img-ratio)
         (< 1 frame-ratio))
    {:height (int (* img-ratio width)) :width width}

    ;; Wide image, tall or square frame
    (< 1 img-ratio)
    {:width (int (* img-ratio height)) :height height}

    ;; Tall image, tall
    (and (< img-ratio 1)
         (< frame-ratio 1))
    {:width (int (* img-ratio height)) :height height}

    ;; Tall image, wide or square frame
    :default
    {:width width :height (int (/ width img-ratio))}))

(defn fit-resize-params [^BufferedImage image {:keys [width height scale-up?]}]
  (let [w (.getWidth image)
        h (.getHeight image)
        params (fit-resize-params-1 (/ w h) (/ width height) width height)]
    (cond
      (and (= w (:width params))
           (= h (:height params))) nil

      (or scale-up?
          (and (<= (:width params) w)
               (<= (:height params) h)))
      params

      :default nil)))

(defn fit-crop-params [^BufferedImage image {:keys [width height offset-y offset-x]}]
  (let [w (.getWidth image)
        h (.getHeight image)]
    (if (and (= w width) (= h height))
      nil
      (crop-params image {:width width
                          :height height
                          :offset-y (or offset-y :center)
                          :offset-x (or offset-x :center)}))))

(defmethod transform :fit [_ image opt]
  (when-not (map? opt)
    (throw (Exception. "Fit requires a map of options")))
  (when (or (nil? (:width opt))
            (nil? (:height opt)))
    (throw (Exception. "Fit requires both a width and a height")))
  (let [resize-params (fit-resize-params image opt)
        image (if resize-params (resize image resize-params) image)]
    (if-let [crop-params (fit-crop-params image opt)]
      (crop image crop-params)
      image)))

(defmethod transform :scale [_ image s]
  (collage/scale image s))

(defn- size-output [{:keys [width height] :as c} image]
  (if (and (nil? width) (nil? height))
    image
    (collage/resize image :width width :height height)))

(defn transform-image
  "Transforms an image according to the transformation specs and returns
  a `BufferedImage`."
  [transformation-config]
  (loop [image (util/load-image (:resource transformation-config))
         [transformation & transformations] (:transformations transformation-config)]
    (if transformation
      (recur (apply transform (first transformation) image (rest transformation)) transformations)
      (size-output transformation-config image))))

(defn write-image
  "Writes `image` with the specified quality parameters to `file-path`.
  Creates necessary parent directories."
  [image {:keys [ext quality progressive?]} file-path]
  (create-folders file-path)
  (cond
    (= :jpg ext)
    (util/save image file-path
               :quality (or quality 1)
               :progressive (or progressive? false))

    (= :png ext) (util/save image file-path)))

(defn transform-image-to-file [transformation file-path]
  (-> (transform-image transformation)
      (write-image transformation file-path)))

(defn- get-ext [file-path transformation]
  (if (or (some #(= :circle (first %)) (:transformations transformation))
          (some #(= :triangle (first %)) (:transformations transformation)))
    "png"
    (last (re-find #"\.([^\.]+)$" file-path))))

(defn content-hash
  "Compute a hash of the contents. If the configuration key
  `:cacheable-urls?` is `false`, the hash will be faster, but less
  correct, as it will only use the file path, and not the actual file
  contents for the hash, along with the transformation configuration.
  Always set `:cacheable-urls?` to `true` in production environments."
  [file-path transform {:keys [transformations cacheable-urls? resource-path]}]
  (digest/sha-1
   (str (pr-str (get transformations transform))
        (if cacheable-urls?
          (slurp (io/resource (str resource-path "/" file-path)))
          (str resource-path "/" file-path)))))

(defn url-to
  "Given a config map, a keyword transform to apply, and the path to a
  file, return a URL that the middleware will recognize and process."
  [config transform file-path]
  (format "/%s/%s/%s/%s.%s"
          (:prefix config)
          (name transform)
          (content-hash file-path transform config)
          (second (re-find #"(.+)\.[^\.]+$" file-path))
          (get-ext file-path (get (:transformations config) transform))))

(defn realize-url
  "Given an an URL that contains only a transformation and a file name,
  use the configuration to fully qualify it with a prefix and content
  hash."
  [config url]
  (let [[_ transform file] (re-find #"/([^/]+)/(.+)$" url)]
    (url-to config (keyword transform) file)))

(def path-re #"/([^\/]+)/([^\/]+)/([^\/]+)/(.+)\.([^\/]+)")

(defn image-spec
  "Parses the image URL to a map describing the desired file, output
  format and transformation."
  [url]
  (let [[_ _ transform _ filename ext] (re-find path-re url)]
    {:transform (keyword transform)
     :filename filename
     :ext (keyword ext)
     :url url}))

(defn- prepare-jpg-for-retina [transformation]
  (cond-> transformation
    (:width transformation) (update :width * 2)
    (:height transformation) (update :height * 2)
    (< 200 (or (:width transformation)
               (:height transformation))) (assoc :progressive true)
    :always (assoc :quality 0.3)))

(defn inflate-spec
  "Given a spec from `image-spec` and a config map, validate and inflate
  the spec so it includes all details necessary to perform the
  transformation."
  [spec config]
  (let [{:keys [transform filename ext url]} spec
        {:keys [transformations resource-path]} config
        transformation (get transformations transform)]
    (when (nil? transformation)
      (throw (Exception. (format "Unknown transform \"%s\" in URL \"%s\", use one of %s" transform url (keys transformations)))))
    (when-not (contains? #{:png :jpg} ext)
      (throw (Exception. (format "Unknown extension \"%s\" in URL \"%s\", use png or jpg" ext url))))
    (when (and (nil? (:width transformation)) (nil? (:height transformation)) (:retina-optimized? transformation))
      (throw (Exception. (format "Cannot optimize \"%s\" for retina when there is no width and/or height set" url))))
    (let [path (str resource-path "/" filename)
          jpg-file (io/resource (str path ".jpg"))
          png-file (io/resource (str path ".png"))]
      (when (and jpg-file png-file)
        (throw (Exception. (format "Found both %s.jpg and %s.png, unable to select input. Please make sure there is only one file under this name" path path))))
      (when (and (nil? jpg-file) (nil? png-file))
        (throw (Exception. (format "Found neither %s.jpg nor %s.png, unable to select input." path path))))
      (let [spec (merge (if (and (= :jpg ext) (:retina-optimized? transformation))
                          (prepare-jpg-for-retina transformation)
                          transformation)
                        {:ext ext
                         :resource (or jpg-file png-file)})]
        (assoc spec :cache-path (cache-path config spec))))))

(defn cached?
  "Given a spec, returns `true` if the image/transformation combo is
  cached on disk, `false` otherwise."
  [spec]
  (-> (:cache-path spec)
      io/file
      .exists))

(defn serve-image
  "Prepare a Ring response for the image described by the request.
  Optionally caches the file on disk for better future performance."
  [req config]
  (let [spec (inflate-spec (image-spec (:uri req)) config)]
    (when-not (and (get config :disk-cache?) (cached? spec))
      (-> spec
          transform-image
          (write-image spec (:cache-path spec))))
    (let [file (io/file (:cache-path spec))]
     {:status 200
      :headers {"last-modified" (last-modified file)}
      :body file})))

(defn image-url?
  "Returns true if the URL is a request for a transformed image - e.g.,
  starts with the configured prefix."
  [url {:keys [prefix]}]
  (= prefix (second (re-find path-re url))))

(defn- image-req? [req asset-config]
  (and (= :get (:request-method req))
       (image-url? (:uri req) asset-config)))

(defn wrap-images
  "Ring middleware - intercept any request to the configured prefix, and
  serve transformed images from it."
  [handler & [config]]
  (fn [req]
    (if (image-req? req config)
      (serve-image req config)
      (handler req))))
