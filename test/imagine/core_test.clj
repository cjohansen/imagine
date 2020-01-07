(ns imagine.core-test
  (:require [imagine.core :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import java.awt.image.BufferedImage))

(defn image [w h]
  (BufferedImage. w h BufferedImage/TYPE_INT_RGB))

(deftest fit-resize-same-size
  (is (nil? (sut/fit-resize-params (image 1024 768) {:width 1024 :height 768}))))

(deftest fit-resize-same-width-too-tall
  (is (nil? (sut/fit-resize-params (image 1024 900) {:width 1024 :height 768}))))

(deftest fit-resize-same-width-too-short
  (is (nil? (sut/fit-resize-params (image 1024 384) {:width 1024 :height 768}))))

(deftest fit-resize-same-width-too-short-can-scale-up
  (is (= (sut/fit-resize-params (image 1024 384) {:width 1024 :height 768 :scale-up? true})
         {:width 2048,:height 768})))

(deftest fit-resize-same-height-too-wide
  (is (nil? (sut/fit-resize-params (image 1224 768) {:width 1024 :height 768}))))

(deftest fit-resize-same-height-too-narrow
  (is (nil? (sut/fit-resize-params (image 800 900) {:width 1024 :height 768}))))

(deftest fit-resize-same-height-too-narrow-can-scale-up
  (is (= (sut/fit-resize-params (image 800 900) {:width 1000 :height 800 :scale-up? true})
         {:width 1000, :height 1125})))

(deftest fit-resize-same-aspect-ratio
  (is (= (sut/fit-resize-params (image 1000 500) {:width 500 :height 250})
         {:width 500, :height 250})))

(deftest fit-resize-same-orientation-too-tall
  (is (= (sut/fit-resize-params (image 1000 500) {:width 300 :height 100})
         {:width 300, :height 150})))

(deftest fit-resize-same-orientation-too-short
  (is (= (sut/fit-resize-params (image 1000 200) {:width 300 :height 100})
         {:width 500, :height 100})))

(deftest fit-resize-opposite-orientation
  (is (= (sut/fit-resize-params (image 600 800) {:width 300 :height 200})
         {:width 300, :height 400})))

(deftest fit-resize-way-too-small
  (is (nil? (sut/fit-resize-params (image 1025 568) {:width 1600 :height 600}))))

(deftest fit-crop-way-too-small
  (is (nil? (sut/fit-crop-params (image 1025 568) {:width 1600 :height 600}))))

(deftest fit-resize-slightly-too-tall
  (is (nil? (sut/fit-resize-params (image 600 801) {:width 800 :height 600}))))

(deftest fit-crop-too-narrow
  (is (= (sut/fit-crop-params (image 1000 700) {:width 1600 :height 600})
         {:width 1000
          :height 600
          :offset-y 50
          :offset-x 0})))

(deftest crop-params-wh-test
  (is (= (sut/crop-params (image 200 200) {:width 100 :height 200})
         {:width 100 :height 200 :offset-x 0 :offset-y 0})))

(deftest crop-params-wh-offset-test
  (is (= (sut/crop-params (image 200 200) {:width 100 :height 200 :offset-x 0 :offset-y 0})
         {:width 100 :height 200 :offset-x 0 :offset-y 0})))

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

(deftest fit-resize-params-same-ratio-test
  (is (= (sut/fit-resize-params (image 1000 800) {:width 500 :height 400})
         {:width 500
          :height 400}))

  (is (nil? (sut/fit-resize-params (image 1000 800) {:width 1000 :height 800})))
  (is (nil? (sut/fit-resize-params (image 1000 800) {:width 2000 :height 1600})))

  (is (= (sut/fit-resize-params (image 1000 800) {:width 2000 :height 1600 :scale-up? true})
         {:width 2000
          :height 1600})))

(deftest fit-resize-params-square-image-test
  (is (= (sut/fit-resize-params (image 1000 1000) {:width 500 :height 400})
         {:width 500
          :height 500}))

  (is (= (sut/fit-resize-params (image 1000 1000) {:width 400 :height 500})
         {:width 500
          :height 500}))

  (is (nil? (sut/fit-resize-params (image 1000 1000) {:width 1600 :height 2000})))

  (is (= (sut/fit-resize-params (image 1000 1000) {:width 1600 :height 2000 :scale-up? true})
         {:width 2000
          :height 2000})))

(deftest fit-resize-params-wide-image-test
  (is (= (sut/fit-resize-params (image 1000 800) {:width 500 :height 500})
         {:width 625
          :height 500}))

  (is (= (sut/fit-resize-params (image 1000 800) {:width 500 :height 250})
         {:width 500
          :height 400}))

  (is (= (sut/fit-resize-params (image 1000 800) {:width 250 :height 500})
         {:width 625
          :height 500}))

  (is (nil? (sut/fit-resize-params (image 1000 800) {:width 1500 :height 1000})))

  (is (= (sut/fit-resize-params (image 1000 800) {:width 1500 :height 1000 :scale-up? true})
         {:width 1500
          :height 1200})))

(deftest fit-resize-params-tall-image-test
  (is (= (sut/fit-resize-params (image 800 1000) {:width 500 :height 500})
         {:width 500
          :height 625}))

  (is (= (sut/fit-resize-params (image 800 1000) {:width 500 :height 250})
         {:width 500
          :height 625}))

  (is (= (sut/fit-resize-params (image 800 1000) {:width 250 :height 500})
         {:width 400
          :height 500}))

  (is (nil? (sut/fit-resize-params (image 800 1000) {:width 1200 :height 500})))

  (is (= (sut/fit-resize-params (image 800 1000) {:width 1200 :height 500 :scale-up? true})
         {:width 1200
          :height 1500})))

(deftest fit-crop-params-fitting-image-test
  (is (nil? (sut/fit-crop-params (image 1000 800) {:width 1000 :height 800}))))

(deftest fit-crop-params-square-image-test
  (is (= (sut/fit-crop-params (image 800 800) {:width 800 :height 600})
         {:width 800
          :height 600
          :offset-y 100
          :offset-x 0}))

  (is (= (sut/fit-crop-params (image 800 800) {:width 800 :height 600 :offset-y :bottom})
         {:width 800
          :height 600
          :offset-y 200
          :offset-x 0})))

(deftest content-hash-test-should-be-idempotent
  (let [config {:transformations {:green-circle [[:crop {:width 200 :height 200}]
                                                 [:circle]]}}]
    (is (= (sut/content-hash "image-1.jpg" :green-circle config)
           (sut/content-hash "image-1.jpg" :green-circle config)))))

(deftest content-hash-different-files-same-config
  (let [config {:transformations {:green-circle [[:crop {:width 200 :height 200}]
                                                 [:circle]]}}]
    (is (not= (sut/content-hash "image-1.jpg" :green-circle config)
              (sut/content-hash "image-2.jpg" :green-circle config)))))

(deftest content-hash-different-configs-same-file
  (let [config {:transformations {:green-circle [[:crop {:width 200 :height 200}]
                                                 [:circle]]}}]
    (is (not= (sut/content-hash "image-1.jpg" :green-circle config)
              (sut/content-hash "image-1.jpg" :red-circle config)))))

(deftest url-to-circle-makes-pngs
  (is (= (-> {:transformations {:circle {:transformations
                                         [[:crop {:width 200 :height 200}]
                                          [:circle]]}}
              :prefix "image-assets"}
             (sut/url-to :circle "photos/myself.jpg"))
         "/image-assets/circle/6fd63fd83c31692da08cea2ffd02d982c5a76e9b/photos/myself.png")))

(deftest url-to-keeps-jpg
  (is (= (-> {:transformations {:square [[:crop {:width 200 :height 200}]]}
              :prefix "image-assets"}
             (sut/url-to :square "photos/myself.jpg"))
         "/image-assets/square/cc9471e730e6b75fb68081b17b079254c0019c1c/photos/myself.jpg")))

(deftest realize-url-test
  (is (= (-> {:transformations {:square [[:crop {:width 200 :height 200}]]}
              :prefix "image-assets"}
             (sut/realize-url "/square/photos/myself.jpg"))
         "/image-assets/square/cc9471e730e6b75fb68081b17b079254c0019c1c/photos/myself.jpg")))

(deftest image-spec-test
  (is (= (sut/image-spec "/image-assets/square/80f3b0/photos/myself.jpg")
         {:transform :square
          :filename "photos/myself"
          :ext :jpg
          :url "/image-assets/square/80f3b0/photos/myself.jpg"})))
