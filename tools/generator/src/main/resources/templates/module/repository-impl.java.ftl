package ${package}.persistence;

import org.springframework.stereotype.Repository;
import ${package}.${type}Repository;

@Repository
class ${type}RepositoryImpl implements ${type}Repository {

    final ${type}Mapper mapper;

    ${type}RepositoryImpl(${type}Mapper mapper) {
        this.mapper = mapper;
    }
}
