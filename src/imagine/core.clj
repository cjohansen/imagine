(ns imagine.core
  (:require [fivetonine.collage.util :as util]
            [fivetonine.collage.core :as collage]
            [imagine.digest :as digest]
            [clojure.java.io :as io])
  (:import java.net.URL
           java.awt.image.BufferedImage))

(defn- last-modified [#^URL resource]
  (let [url-connection (.openConnection resource)
        modified (.getLastModified url-connection)]
    (.close (.getInputStream url-connection))
    modified))

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

(defn crop-params [^BufferedImage image {:keys [width height preset offset-x offset-y] :as opt}]
  (if (= :square preset)
    (let [w (.getWidth image)
          h (.getHeight image)
          min-dimension (min w h)
          cropped-offset (- (/ (max w h) 2) (/ min-dimension 2))
          [off-x off-y] (if (> w h)
                          [cropped-offset 0]
                          [0 cropped-offset])]
      {:offset-x (or offset-x off-x)
       :offset-y (or offset-y off-y)
       :width min-dimension
       :height min-dimension})
    opt))

(defmethod transform :crop [_ image opts]
  (let [{:keys [width height offset-x offset-y]} (crop-params image opts)]
    (collage/crop image offset-x offset-y width height)))

(defmethod transform :triangle [_ image position]
  (collage/triangle image position))

(defmethod transform :circle [_ image position]
  ;; position not yet implemented
  (collage/circle image))

(defmethod transform :grayscale [_ image]
  (collage/grayscale image))

(defmethod transform :duotone [_ image from-color to-color]
  (collage/duotone image from-color to-color))

(defmethod transform :rotate [_ image theta]
  (collage/rotate image theta))

(defmethod transform :resize [_ image {:keys [width height]}]
  (collage/resize image :width width :height height))

(defmethod transform :scale [_ image s]
  (collage/scale image s))

(defn- size-output [{:keys [width height]} image]
  (if (and (nil? width) (nil? height))
    image
    (collage/resize image :width width :height height)))

(defn transform-image
  "Transforms an image according to the transformation specs and returns
  a `BufferedImage`."
  [transformation]
  (loop [image (util/load-image (:resource transformation))
         [transformation & transformations] (:transformations transformation)]
    (if transformation
      (recur (apply transform (first transformation) image (rest transformation)) transformations)
      (size-output transformation image))))

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
    (< 200 (:width transformation)) (assoc :progressive true)
    :always (assoc :quality 0.3)))

(defn inflate-spec
  "Given a spec from `image-spec` and a config map, validate and inflate
  the spec so it includes all details necessary to perform the
  transformation."
  [{:keys [transform filename ext url]}
   {:keys [transformations retina-optimized? resource-path width height] :as config}]
  (let [transformation (get transformations transform)]
    (when (nil? transformation)
      (throw (Exception. (format "Unknown transform \"%s\" in URL \"%s\", use one of %s" transform url (keys transformations)))))
    (when-not (contains? #{:png :jpg} ext)
      (throw (Exception. (format "Unknown extension \"%s\" in URL \"%s\", use png or jpg" ext url))))
    (when (and (nil? width) (nil? height) retina-optimized?)
      (throw (Exception. (format "Cannot optimize \"%s\" for retina when there is no width and/or height set" url))))
    (let [path (str resource-path "/" filename)
          jpg-file (io/resource (str path ".jpg"))
          png-file (io/resource (str path ".png"))]
      (when (and jpg-file png-file)
        (throw (Exception. (format "Found both %s.jpg and %s.png, unable to select input. Please make sure there is only one file under this name" path path))))
      (when (and (nil? jpg-file) (nil? png-file))
        (throw (Exception. (format "Found neither %s.jpg nor %s.png, unable to select input." path path))))
      (let [spec {:transformation (if (and (= :jpg ext) retina-optimized?)
                                    transformation
                                    transformation)
                  :ext ext
                  :resource (or jpg-file png-file)}]
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