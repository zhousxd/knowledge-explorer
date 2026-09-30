package com.ke.service.llm;

/** 模型档位：router 走轻量模型做路由/分类，generator 走主力模型做内容生成（02 §12.4） */
public enum ModelTier { ROUTER, GENERATOR }
