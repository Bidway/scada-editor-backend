package com.example.channel.mapper;

import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.NodeDto;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface NodeMapper {
    @Mapping(target = "idNode", expression = "java(dto.getParentId() + \".\" + dto.getIdNode())")
    Node toEntity(CreateNodeDto dto);


    @Mapping(target = "name", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "idNode", source = "param.node.idNode")
    ParamDto toDto(NodeParam param, List<Description> descriptions);
    @AfterMapping
    default void fillDescription(
            @MappingTarget ParamDto dto,
            NodeParam param,
            List<Description> descriptions
    ) {
        int index = param.getIdType().intValue() - 1;

        if (index >= 0 && index < descriptions.size()) {
            dto.setName(descriptions.get(index).getName());
            dto.setType(descriptions.get(index).getType());
        }
    }

    @Mapping(target = "idNode", source = "idNode")
    @Mapping(target = "parentId", expression = "java(getParentId(node.getIdNode()))")
    NodeDto toDto(Node node);

    default String getParentId(String idNode) {
        if (idNode == null || !idNode.contains(".")) {
            return null;
        }
        return idNode.substring(0, idNode.lastIndexOf("."));
    }

    @Mapping(target = "id", source = "nodeParam.id")
    @Mapping(target = "idNode", source = "nodeParam.node.idNode")
    @Mapping(target = "value", source = "nodeParam.value")
    @Mapping(target = "name", source = "description.name")
    @Mapping(target = "type", source = "description.type")
    ParamDto toDto(NodeParam nodeParam,Description description);

}