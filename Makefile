test:
	./bin/kaocha

imagine.jar: src/imagine/*.clj
	clojure -A:jar

deploy: test imagine.jar
	mvn deploy:deploy-file -Dfile=imagine.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml
