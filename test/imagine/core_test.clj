(ns imagine.core-test
  (:require [imagine.core :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import java.awt.image.BufferedImage))

(deftest crop-params-wh-test
  (is (= (sut/crop-params nil {:width 100 :height 200})
         {:width 100 :height 200})))

(deftest crop-params-wh-offset-test
  (is (= (sut/crop-params nil {:width 100 :height 200 :offset-x 0 :offset-y 0})
         {:width 100 :height 200 :offset-x 0 :offset-y 0})))

(defn image [w h]
  (BufferedImage. w h BufferedImage/TYPE_INT_ARGB))

(deftest square-crop-params-test
  (is (= (sut/crop-params (image 100 200) {:preset :square})
         {:width 100 :height 100 :offset-x 0 :offset-y 50})))

(deftest square-crop-top-test
  (is (= (sut/crop-params (image 100 200) {:preset :square :offset-y :top})
         {:width 100 :height 100 :offset-x 0 :offset-y 0})))

(deftest square-crop-bottom-test
  (is (= (sut/crop-params (image 100 200) {:preset :square :offset-y :bottom})
         {:width 100 :height 100 :offset-x 0 :offset-y 100})))

(deftest square-crop-center-test
  (is (= (sut/crop-params (image 100 200) {:preset :square :offset-y :center})
         {:width 100 :height 100 :offset-x 0 :offset-y 50})))

(deftest square-crop-left-test
  (is (= (sut/crop-params (image 200 100) {:preset :square :offset-x :left})
         {:width 100 :height 100 :offset-x 0 :offset-y 0})))

(deftest square-crop-right-test
  (is (= (sut/crop-params (image 200 100) {:preset :square :offset-x :right})
         {:width 100 :height 100 :offset-x 100 :offset-y 0})))

(deftest square-crop-horizontal-center-test
  (is (= (sut/crop-params (image 200 100) {:preset :square :offset-x :center})
         {:width 100 :height 100 :offset-y 0 :offset-x 50})))

(deftest square-crop-right-bottom
  (is (= (sut/crop-params (image 500 1000) {:width 400
                                            :height 300
                                            :offset-x :right
                                            :offset-y :bottom})
         {:width 400
          :height 300
          :offset-x 100
          :offset-y 700})))

(deftest content-hash-test-should-be-idempotent
  (is (= (sut/content-hash "image-1.jpg" :green-circle config)
         (sut/content-hash "image-1.jpg" :green-circle config))))

(deftest content-hash-different-files-same-config
  (is (not= (sut/content-hash "image-1.jpg" :green-circle config)
            (sut/content-hash "image-2.jpg" :green-circle config))))

(deftest content-hash-different-configs-same-file
  (is (not= (sut/content-hash "image-1.jpg" :green-circle config)
            (sut/content-hash "image-1.jpg" :red-circle config))))

(deftest url-to-circle-makes-pngs
  (is (= (sut/url-to config :green-circle "photos/myself.jpg")
         "/image-assets/green-circle/a7c416925aa150ed8b3dce7888e0294500553220/photos/myself.png")))

(deftest url-to-keeps-jpg
  (is (= (sut/url-to config :square "photos/myself.jpg")
         "/image-assets/square/80f3b08338e02af4b0e796110f94aca934e4c65f/photos/myself.jpg")))

(deftest realize-url-test
  (is (= (sut/realize-url config "/square/photos/myself.jpg")
         "/image-assets/square/80f3b08338e02af4b0e796110f94aca934e4c65f/photos/myself.jpg")))

(deftest image-spec-test
  (is (= (sut/image-spec "/image-assets/square/80f3b0/photos/myself.jpg")
         {:transform :square
          :filename "photos/myself"
          :ext :jpg
          :url "/image-assets/square/80f3b0/photos/myself.jpg"})))
