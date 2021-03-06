setlocal enabledelayedexpansion
set SCRIPTPATH=%%~dp0
set JETTYFOLDER=%%SCRIPTPATH%%\%s
set LOG4J2="file:\%%JETTYFOLDER%%\webapps\ROOT\etc\log4j2.xml"
set replace=%%%%20
set LOG4J2=%%LOG4J2: =!replace!%%

java -jar "%%JETTYFOLDER%%\start.jar" jetty.base="%%JETTYFOLDER%%" -Dorg.eclipse.jetty.server.Request.maxFormContentSize=100000000 -Djetty.port=10214 -Dcom.bigdata.rdf.sail.webapp.ConfigParams.propertyFile="%%SCRIPTPATH%%\RWStore.properties" -Dcom.metaphacts.config.baselocation="%%SCRIPTPATH%%\config" -DruntimeDirectory="%%SCRIPTPATH%%\" -Dlog4j.configurationFile=%%LOG4J2%%