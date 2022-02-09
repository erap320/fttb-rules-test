# FTTB rules test
This basic application can be used to inject some flow rules in ONOS

## How to use
The rules and their installation are defined in `src/main/java/org/opencord/fttbtest/AppComponent.java`.
To change the set of rules installed by this app, change the parameters passed at lines `425` and `431`.

Assuming the `onos-app` tool is in your PATH, you can execute the following commands from the root of this repository:

### Install rules
```
make
```

### Remove rules
```
make stop
```