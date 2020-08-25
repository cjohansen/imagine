test: src/imagine/*.clj test/imagine/*.clj deps.edn
	./bin/kaocha

imagine.jar: src/imagine/*.clj deps.edn
	clojure -A:jar

deploy: test imagine.jar
	mvn deploy:deploy-file -Dfile=imagine.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

.phony: test
