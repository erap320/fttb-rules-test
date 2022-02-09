all: build install

build:
	mvn clean install -Dcheckstyle.skip

install:
	onos-app localhost reinstall! target/fttb-test-1.0-SNAPSHOT.oar

stop:
	onos-app localhost deactivate org.opencord.fttbtest
