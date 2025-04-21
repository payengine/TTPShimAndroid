# TTPShimAndroid

To get the PayEngine's package, setup the credential and registry in your project:

`local.properties`

```
mavenUsername=<PE-MAVEN-USER>
mavenPassword=<PE-MAVEN-PASSWORD>
```

## How to use it in your code
- Copy `PESoftPOSShim.kt` in your project and adjust the packge name
- Use `runTransaction`  method from `SimpleView.kt` create a simple flow of transaction processing in your app
