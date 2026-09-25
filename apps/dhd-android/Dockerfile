FROM ghcr.io/cirruslabs/android-sdk:35

WORKDIR /workspace

COPY contracts ./contracts
COPY apps/dhd-android ./apps/dhd-android

WORKDIR /workspace/apps/dhd-android

RUN ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
