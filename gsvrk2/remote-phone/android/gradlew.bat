@rem
@rem Copyright 2015 the original author or authors.
@rem
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
goto execute

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

:execute
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
