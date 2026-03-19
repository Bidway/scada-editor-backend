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






    @Mapping(target = "idNode", ignore = true)
    Node toEntity(CreateNodeDto dto);

    @AfterMapping
    default void setIdNode(CreateNodeDto dto, @MappingTarget Node node) {
        if (dto.getParentId() == null || dto.getParentId().isBlank()) {
            node.setIdNode(dto.getIdNode());
        } else {
            node.setIdNode(dto.getParentId() + "." + dto.getIdNode());
        }
    }

    @Named("setIdNode")
    default String setIdNode(String idNode, String parentKey) {
        if (idNode == null || !idNode.contains(".")) {
            return null;
        }
        if (parentKey == null)
            return idNode;
        return (parentKey+"+"+idNode);
    }



    @Mapping(target = "name", ignore = true)
    @Mapping(target = "type", ignore = true)
//    @Mapping(target = "idNode", source = "idNode")
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
    NodeDto toDto(Node node);

    @Named("getParentId")
    default String getParentId(String idNode) {
        if (idNode == null || !idNode.contains(".")) {
            return null;
        }
        return idNode.substring(0, idNode.lastIndexOf("."));
    }

    @Mapping(target = "id", source = "nodeParam.id")
//    @Mapping(target = "idNode", source = "idNode")
    @Mapping(target = "value", source = "nodeParam.value")
    @Mapping(target = "name", source = "description.name")
    @Mapping(target = "type", source = "description.type")
    ParamDto toDto(NodeParam nodeParam,Description description);

}