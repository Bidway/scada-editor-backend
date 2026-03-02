package com.example.scadaeditorbackend.channelbase.mapper;

import com.example.scadaeditorbackend.channelbase.dto.nodeDto.CreateNodeDto;
import com.example.scadaeditorbackend.channelbase.dto.nodeDto.NodeDto;
import com.example.scadaeditorbackend.channelbase.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.channelbase.model.Description;
import com.example.scadaeditorbackend.channelbase.model.Node;
import com.example.scadaeditorbackend.channelbase.model.NodeParam;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface NodeMapper {
    Node toEntity(NodeDto dto);

    @Mapping(target = "nodeType", source = "type")
    @Mapping(target = "idNode", constant = "temp")
    Node toEntity(CreateNodeDto dto);
    @Mapping(target = "isParent", source = "isParent")
    NodeDto toDto(Node entity, Boolean isParent);


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

    @Mapping(target = "isParent", ignore = true) // вычисляем вручную
    @Mapping(target = "idNode", source = "idNode")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "parentId", source = "parentId")
    NodeDto toDto(Node node);

    @AfterMapping
    default void setIsParent(@MappingTarget NodeDto dto, Node node) {
        if (node.getIdNode() != null && node.getIdNode().length() >= 3) {
            dto.setIsParent(node.getIdNode().substring(0,3).equals("cha"));
        } else {
            dto.setIsParent(false);
        }
    }
    @Mapping(target = "id", source = "nodeParam.id")
    @Mapping(target = "idNode", source = "nodeParam.node.idNode")
    @Mapping(target = "value", source = "nodeParam.value")
    @Mapping(target = "name", source = "description.name")
    @Mapping(target = "type", source = "description.type")
    ParamDto toDto(NodeParam nodeParam,Description description);


//    void updateEntityFromDto(NodeDto dto, @MappingTarget Node entity);

}