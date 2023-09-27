(ns imagine.core
  (:require [clojure.java.io :as io]
            [fivetonine.collage.core :as collage]
            [fivetonine.collage.util :as util]
            [imagine.digest :as digest])
  (:import java.awt.color.ColorSpace
           [java.awt.image BufferedImage ColorConvertOp]
           java.io.File
           java.nio.file.Paths))

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
  (.toString
   (Paths/get
    (or (:tmpdir config) default-tmpdir)
    (into-array [(str (digest/sha-1 (pr-str (if (:cacheable-urls? config)
                                              (update spec :resource slurp)
                                              (update spec :resource #(.getPath %)))))
                      "."
                      (name (:ext spec)))]))))

(defmulti transform (fn [transformation image & args] transformation))

(defn- resolve-offset [opt k origin-k image-dim crop-k [start center end]]
  (let [offset (or (opt origin-k) (opt k))
        crop-dim (opt crop-k)]
    (dissoc
     (cond
       (number? offset) opt
       (or (= start offset) (nil? offset)) (assoc opt k 0)
       (= center offset) (assoc opt k (int (/ (- image-dim crop-dim) 2)))
       (= end offset) (assoc opt k (- image-dim crop-dim)))
     :preset)))

(defn- resolve-origin [opt & [default]]
  (let [[default-x default-y] (or default [:left :top])]
    (-> opt
        (assoc :origin-x (or (:origin-x opt) (get-in opt [:origin 0]) (:offset-x opt) default-x))
        (assoc :origin-y (or (:origin-y opt) (get-in opt [:origin 1]) (:offset-y opt) default-y)))))

(defn- resolve-crop-presets [opt width height]
  (if (= :square (:preset opt))
    (let [dim (min width height)]
      (resolve-origin (merge opt {:width dim :height dim}) [:center :center]))
    opt))

(defn- resolve-dimensions [opt w h]
  (cond
    (nil? (:width opt))
    (assoc opt :width (int (* (/ w h) (:height opt))))

    (nil? (:height opt))
    (assoc opt :height (int (* (/ h w) (:width opt))))

    :default opt))

(defn- too-small-to-crop? [w h {:keys [width height]}]
  (or (< w width)
      (< h height)))

(defn- constrain-crop-opts [params width height opts]
  (when (and (not (get opts :allow-smaller? true))
             (too-small-to-crop? width height params))
    (throw (Exception. (format "Tried to crop %sx%s image to %sx%s, but does not :allow-smaller? %s"
                               width height
                               (:width params) (:height params)
                               params))))
  (-> params
      (update :width #(min width %))
      (update :height #(min height %))))

(defn crop-params [^BufferedImage image opt]
  (let [w (.getWidth image)
        h (.getHeight image)]
    (-> opt
        (resolve-crop-presets w h)
        (resolve-dimensions w h)
        (resolve-origin)
        (resolve-offset :offset-x :origin-x w :width [:left :center :right])
        (resolve-offset :offset-y :origin-y h :height [:top :center :bottom])
        (constrain-crop-opts w h opt)
        (dissoc :origin-x :origin-y :origin))))

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

(defn- fit-resize-params-1 [img-w img-h frame-w frame-h]
  (let [by-width-factor (/ frame-w img-w)
        [bww bwh] [(* by-width-factor img-w) (* by-width-factor img-h)]
        by-height-factor (/ frame-h img-h)
        [bhw bhh] [(* by-height-factor img-w) (* by-height-factor img-h)]]
    (if (or (< bww frame-w) (< bwh frame-h))
      {:width bhw :height bhh}
      {:width bww :height bwh})))

(defn fit-resize-params [^BufferedImage image {:keys [width height scale-up?]}]
  (let [w (.getWidth image)
        h (.getHeight image)]
    (if (or (and (= width w)
                 (<= height h))
            (and (<= width w)
                 (= height h)))
      nil
      (let [params (fit-resize-params-1 w h width height)]
        (if (or scale-up?
             (and (<= (:width params) w)
                  (<= (:height params) h)))
          (-> params
              (update :width int)
              (update :height int))
          nil)))))

(defn fit-crop-params [^BufferedImage image {:keys [width height offset-y offset-x]}]
  (let [w (.getWidth image)
        h (.getHeight image)]
    (if (and (<= w width) (<= h height))
      nil
      (crop-params image {:width (min w width)
                          :height (min h height)
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

(defn- size-output [{:keys [width height] :as c} ^BufferedImage image]
  (if (or (and (nil? width) (nil? height))
          (< (.getWidth image) (or width (.getWidth image)))
          (< (.getHeight image) (or height (.getHeight image))))
    image
    (collage/resize image :width width :height height)))

(defn validate-transformation-image-size [image {:keys [transformations]}]
  (let [crops (->> transformations
                   (filter (comp #{:fit :crop} first))
                   (map second)
                   (remove (comp nil? :width))
                   (remove :scale-up?)
                   (map (juxt :width :height)))]
    (when (seq crops)
      (let [max-w (some->> crops (map first) (remove nil?) seq (apply max))
            max-h (some->> crops (map second) (remove nil?) seq (apply min))]
        (when (or (< (.getWidth image) max-w)
                  (< (.getHeight image) max-h))
          (throw
           (ex-info
            "Transformation asks for dimensions larger than source image and :scale-up? is false"
            {:transform-width max-w
             :transform-height max-h
             :image-width (.getWidth image)
             :image-height (.getHeight image)})))))))

(defn transform-image
  "Transforms an image according to the transformation specs and returns
  a `BufferedImage`."
  [transformation-config]
  (let [image (util/load-image (:resource transformation-config))]
    (validate-transformation-image-size image transformation-config)
    (loop [image image
           [transformation & transformations] (:transformations transformation-config)]
      (if transformation
        (recur (apply transform (first transformation) image (rest transformation)) transformations)
        (size-output transformation-config image)))))

(defn- get-ext [file-path transformations]
  (if (or (some #(= :circle (first %)) transformations)
          (some #(= :triangle (first %)) transformations))
    "png"
    (last (re-find #"\.([^\.]+)$" file-path))))

(defn write-image
  "Writes `image` with the specified quality parameters to `file-path`.
  Creates necessary parent directories."
  [^BufferedImage image {:keys [ext quality progressive? width height transformations] :as spec} file-path]
  (let [[_ requested-ext] (re-find #"\.([^\.]+)$" file-path)
        required-ext (get-ext file-path transformations)]
    (when-not (= required-ext requested-ext)
      (throw (Exception. (format "Tried to load %s as %s, but transformations specifies or requires it to be %s"
                                 (:resource spec) requested-ext required-ext)))))
  (create-folders file-path)
  (let [quality (if (or (< (.getWidth image) (or width (.getWidth image)))
                        (< (.getHeight image) (or height (.getHeight image))))
                  1
                  quality)]
    (cond
      (= :jpg ext)
      (util/save image file-path
                 :quality (or quality 1)
                 :progressive (or progressive? false))

      (= :png ext) (util/save image file-path))))

(defn transform-image-to-file [transformation file-path]
  (try
    (-> (transform-image transformation)
        (write-image transformation file-path))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (format "Failed to transform %s to disk: %s"
                              (some-> transformation :resource .getPath)
                              (.getMessage e))
                      (merge (ex-data e)
                             {:file file-path}
                             transformation))))))

(defn content-hash
  "Compute a hash of the contents. If the configuration key `:cacheable-urls?` is
  `false`, the hash will be faster, but less accurate, as it will only use the
  file path and mtime, and not the actual file contents for the hash, along with
  the transformation configuration. Always set `:cacheable-urls?` to `true` in
  production environments."
  [file-path transform {:keys [transformations cacheable-urls? resource-path]}]
  (let [resource-path (.toString (Paths/get (or resource-path "") (into-array [file-path])))
        resource (io/resource resource-path)]
    (if (nil? resource)
      (throw
       (ex-info
        (format "Failed to generate content-hash: image %s not found" resource-path)
        {:image resource-path}))
      (digest/sha-1
       (str (pr-str (get transformations transform))
            (if cacheable-urls?
              (slurp resource)
              (str resource-path "/" file-path "/" (.lastModified (io/file resource)))))))))

(defn url-to
  "Given a config map, a keyword transform to apply, and the path to a
  file, return a URL that the middleware will recognize and process."
  [config transform file-path]
  (when-let [transformation (get (:transformations config) transform)]
    (format "/%s/%s/%s/%s.%s"
            (:prefix config)
            (name transform)
            (content-hash file-path transform config)
            (second (re-find #"(.+)\.[^\.]+$" file-path))
            (get-ext file-path (:transformations transformation)))))

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
    :always (assoc :quality (get transformation :retina-quality 0.3))))

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
      (let [spec (merge spec
                        (if (and (= :jpg ext) (:retina-optimized? transformation))
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

(defn get-transformed-image [spec config]
  (when-not (and (get config :disk-cache?) (cached? spec))
    (try
      (-> spec
          transform-image
          (write-image spec (:cache-path spec)))
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (format "Failed to serve %s with the %s transform: %s"
                                (some-> spec :resource .getPath)
                                (:transform spec)
                                (.getMessage e))
                        (merge (ex-data e)
                               (dissoc spec :resource)
                               {:file (some-> spec :resource .getPath)}))))))
  (io/file (:cache-path spec)))

(defn get-image-from-url [config url]
  (-> (inflate-spec (image-spec url) config)
      (get-transformed-image config)))

(defn serve-image
  "Prepare a Ring response for the image described by the request.
  Optionally caches the file on disk for better future performance."
  [req config]
  (let [file (get-image-from-url config (:uri req))]
    {:status 200
     :headers {"last-modified" (last-modified file)}
     :body file}))

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
  (fn
    ([req]
     (if (image-req? req config)
       (serve-image req config)
       (handler req)))
    ([req respond raise]
     (if (image-req? req config)
       (try
         (respond (serve-image req config))
         (catch Exception e
           (raise e)))
       (handler req respond raise)))))

(comment

  (def config
    {:prefix "image-assets"
     :resource-path "public"
     :transformations
     {:identity {}
      :crazy {:transformations [[:crop :square]
                                [:duotone [120 0 0] [0 255 0]]]
              :width 300}
      :vcard {:transformations [[:crop {:preset :square}]]
              :retina-optimized? true
              :width 92}

      :vertigo {:height 850
                :retina-optimized? true}

      :big {:transformations [[:crop {:preset :square}]
                              [:circle]
                              [:triangle :bottom-right]]
            :width 666}}})

  (get-image-from-url config "/image-assets/vertigo/_/puffins.jpg")

  (image-spec "/image-assets/vertigo/_/puffins.jpg")
  (realize-url config "/vertigo/puffins.jpg")

  (util/load-image (:resource spec))

  (-> {:transformations
       [[:fit {:width 983 :height 400 :offset-y :top}]]
       :ext :jpg
       :resource (clojure.java.io/file "/Users/christian/Downloads/3-Wednesday-Best.jpg")
       :cache-path "/tmp/spaghetti.jpg"}
      (transform-image-to-file "/tmp/fit.png"))

  (-> {:transformations
       [[:crop {:preset :square}]
        [:resize {:width 774}]
        [:duotone [255 82 75] [255 255 255]]
        [:circle]
        [:triangle :lower-left]
        [:crop {:width 666 :offset-y :bottom}]]
       :ext :jpg
       :resource (clojure.java.io/file "/Users/christian/Downloads/spaghetti.jpg")
       :cache-path "/tmp/spaghetti.jpg"}
      (transform-image-to-file "/tmp/bruce-top.png"))

  )
