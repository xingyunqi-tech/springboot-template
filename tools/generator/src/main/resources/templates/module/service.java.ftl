package ${package};

import org.springframework.stereotype.Service;

@Service
public class ${type}Service {

    final ${type}Repository repository;

    public ${type}Service(${type}Repository repository) {
        this.repository = repository;
    }
}
