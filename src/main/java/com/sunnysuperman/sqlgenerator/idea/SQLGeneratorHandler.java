package com.sunnysuperman.sqlgenerator.idea;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.psi.*;
import com.sunnysuperman.sqlgenerator.idea.SQLGenerator.TableColumn;
import com.sunnysuperman.sqlgenerator.idea.SQLGenerator.TableDefinition;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class SQLGeneratorHandler extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Component component = e.getInputEvent().getComponent();
        // 获取当前选中的类文件
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        if (!(psiFile instanceof PsiJavaFile)) {
            alert(component, "请选择Java实体类");
            return;
        }
        PsiJavaFile javaFile = (PsiJavaFile) psiFile;
        PsiClass[] classes = javaFile.getClasses();
        if (classes.length == 0) {
            alert(component, "请选择Java实体类");
            return;
        }
        // 遍历文件中的类
        String sql;
        try {
            sql = generateSQLByJavaClass(classes[0]);
        } catch (SQLGenerationException ex) {
            alert(component, ex.getMessage());
            return;
        }
        showSqlDialog(sql);
    }

    private String generateSQLByJavaClass(PsiClass type) throws SQLGenerationException {
        PsiAnnotation entityAnnotation = type.getAnnotation("com.sunnysuperman.repository.annotation.Entity");
        if (entityAnnotation == null) {
            throw new SQLGenerationException("类未标记@Entity");
        }
        PsiAnnotation tableAnnotation = type.getAnnotation("com.sunnysuperman.repository.annotation.Table");
        if (tableAnnotation == null) {
            throw new SQLGenerationException("类未标记@Table");
        }
        // 表定义
        TableDefinition def = new TableDefinition();
        def.setName(AnnotationUtils.getStringValue(tableAnnotation, "name"));
        def.setComment(AnnotationUtils.getStringValue(tableAnnotation, "comment"));
        def.setMapCamelToUnderscore(AnnotationUtils.getBooleanValue(tableAnnotation, "mapCamelToUnderscore", true));
        def.setColumns(new ArrayList<>());
        // 遍历父类的字段
        List<PsiClass> superTypeList = new ArrayList<>(3);
        PsiClass superType = type.getSuperClass();
        while (superType != null) {
            superTypeList.add(0, superType);
            superType = superType.getSuperClass();
        }
        for (PsiClass theSuperType : superTypeList) {
            iterateFields(theSuperType, def);
        }
        // 遍历本类的字段
        iterateFields(type, def);
        // 最终生成SQL
        return SQLGenerator.generate(def);
    }

    private void iterateFields(PsiClass type, TableDefinition def) throws SQLGenerationException {
        for (PsiField field : type.getFields()) {
            PsiAnnotation columnAnnotation = field.getAnnotation("com.sunnysuperman.repository.annotation.Column");
            if (columnAnnotation == null) {
                continue;
            }
            TableColumn column = new TableColumn();
            def.getColumns().add(column);
            column.setName(AnnotationUtils.getStringValue(columnAnnotation, "name"));
            column.setComment(AnnotationUtils.getStringValue(columnAnnotation, "comment"));
            column.setJavaName(field.getName());
            column.setJavaType(getFieldJavaType(field));
            column.setColumnDefinition(AnnotationUtils.getStringArrayValue(columnAnnotation, "columnDefinition"));
            column.setNullable(AnnotationUtils.getBooleanValue(columnAnnotation, "nullable", true));
            column.setLength(AnnotationUtils.getIntValue(columnAnnotation, "length", 255));
            column.setPrecision(AnnotationUtils.getIntValue(columnAnnotation, "precision", 2));
            PsiAnnotation idAnnotation = field.getAnnotation("com.sunnysuperman.repository.annotation.Id");
            if (idAnnotation != null) {
                column.setNullable(false);
                column.setPrimary(true);
                column.setAutoIncrement(Objects.equals("INCREMENT",
                        AnnotationUtils.getEnumValue(idAnnotation, "strategy")));
            }
            PsiAnnotation versionAnnotation = field.getAnnotation("com.sunnysuperman.repository.annotation.VersionControl");
            if (versionAnnotation != null) {
                column.setNullable(false);
            }
        }
    }

    private String getFieldJavaType(PsiField field) throws SQLGenerationException {
        PsiType fieldType = field.getType();
        PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
        if (fieldClass == null) {
            throw new SQLGenerationException("请确保类编译通过");
        }
        // 枚举类统一转成Enumeration
        if (fieldClass.isEnum()) {
            return Enumeration.class.getName();
        }
        if (field.getAnnotation("com.sunnysuperman.repository.annotation.ManyToOne") != null ||
                field.getAnnotation("com.sunnysuperman.repository.annotation.OneToOne") != null) {
            PsiField relatedIdField = findIdField(fieldClass);
            if (relatedIdField != null) {
                return getFieldJavaType(relatedIdField);
            }
        }
        return fieldClass.getQualifiedName();
    }

    private PsiField findIdField(PsiClass fieldClass) {
        return Stream.of(fieldClass.getFields()).filter(field -> field.getAnnotation("com.sunnysuperman.repository.annotation.Id") != null).findAny().orElse(null);
    }

    private void showSqlDialog(String sql) {
        JDialog dialog = new JDialog();
        // 显示对话框
        JTextArea textArea = new JTextArea(sql);
        JScrollPane scrollPane = new JScrollPane(textArea);
        JButton copyButton = new JButton("拷贝SQL");
        copyButton.addActionListener(event -> {
            copyToClipboard(sql);
            alert(dialog, "已拷贝");
            dialog.dispose();
        });
        // 构建并显示对话框
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(copyButton, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private void copyToClipboard(String sql) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sql), null);
    }

    private void alert(Component component, String msg) {
        JOptionPane.showMessageDialog(component, msg);
    }
}
