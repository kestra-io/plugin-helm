# for dev purposes only
#
# Pinned rather than :latest, which now resolves to Kestra 2.x while this plugin builds
# against the 1.3.x platform (see kestraVersion in gradle.properties). Running the two
# together fails at runtime with NoSuchMethodError on classes whose signatures changed
# between majors, such as io.kestra.core.models.assets.AssetsDeclaration.
FROM kestra/kestra:v1.3.39

# COPY build/libs/* /app/plugins/ # this is already handled in docker-compose.yml
