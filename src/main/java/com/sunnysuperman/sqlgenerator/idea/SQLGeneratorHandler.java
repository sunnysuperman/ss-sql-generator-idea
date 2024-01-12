package com.sunnysuperman.sqlgenerator.idea;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.sunnysuperman.sqlgenerator.idea.SQLGenerator.TableColumn;
import com.sunnysuperman.sqlgenerator.idea.SQLGenerator.TableDefinition;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;


public class SQLGeneratorHandler extends AnAction {
    private static final Logger LOG = Logger.getInstance(SQLGeneratorHandler.class);

    @Override
    public void actionPerformed(AnActionEvent e) {
        // 获取当前选中的元素
        PsiElement psiElement = e.getData(CommonDataKeys.PSI_ELEMENT);
        // 选择类或包
        List<String> sqlList = new ArrayList<>();
        try {
            if (psiElement instanceof PsiClass) {
                // 如果选中的是一个类
                PsiClass psiClass = (PsiClass) psiElement;
                String sql = generateSQLByJavaClass(psiClass, true);
                if (sql != null) {
                    sqlList.add(sql);
                }
                showSql(sqlList);
            } else if (psiElement instanceof PsiDirectory) {
                // 如果选中的是一个目录，检查它是否代表一个包
                PsiDirectory psiDirectory = (PsiDirectory) psiElement;
                PsiPackage psiPackage = JavaDirectoryService.getInstance().getPackage(psiDirectory);
                if (psiPackage != null) {
                    traversePackageInBackground(psiPackage, sqlList);
                }
            }
        } catch (SQLGenerationException ex) {
            alert(ex.getMessage());
        } catch (Exception ex) {
            LOG.error("SQLGeneratorHandler error", ex);
        }
    }

    private void traversePackageInBackground(PsiPackage psiPackage, List<String> sqlList) {
        Project project = psiPackage.getProject();
        // 创建后台任务
        Task.Backgroundable task = new Task.Backgroundable(project, "Traversing package", true) {
            public void run(ProgressIndicator progressIndicator) {
                // 设置进度条的初始值和最大值
                progressIndicator.setFraction(0.0);
                progressIndicator.setIndeterminate(false);
                // 在这里执行耗时操作
                ReadAction.run(() -> {
                    traversePackage(psiPackage, sqlList, progressIndicator);
                    if (!progressIndicator.isCanceled()) {
                        showSql(sqlList);
                    }
                });
                // 当任务完成时更新进度条状态
                progressIndicator.setFraction(1.0);
            }
        };
        // 运行任务
        ProgressManager.getInstance().run(task);
    }

    private void traversePackage(PsiPackage psiPackage, List<String> sqlList, ProgressIndicator progressIndicator) {
        // 检查是否请求取消操作
        if (progressIndicator.isCanceled()) {
            return;
        }
        // 更新进度信息
        progressIndicator.setText("遍历包生成SQL: " + psiPackage.getQualifiedName());
        // 遍历包中的所有类
        for (PsiClass psiClass : psiPackage.getClasses()) {
            try {
                String sql = generateSQLByJavaClass(psiClass, false);
                if (sql != null) {
                    sqlList.add(sql);
                }
            } catch (SQLGenerationException ex) {
                // ignore
            }
        }
        // 递归遍历子包
        for (PsiPackage subPackage : psiPackage.getSubPackages()) {
            traversePackage(subPackage, sqlList, progressIndicator);
        }
    }

    private void showSql(List<String> sqlList) {
        if (sqlList.isEmpty()) {
            alert("请选择Java实体类或所在包");
            return;
        }
        // 弹框提示
        showSqlDialog(String.join("\n\n", sqlList));
    }

    private String generateSQLByJavaClass(PsiClass type, boolean throwsOnError) throws SQLGenerationException {
        PsiAnnotation entityAnnotation = type.getAnnotation("com.sunnysuperman.repository.annotation.Entity");
        if (entityAnnotation == null) {
            if (throwsOnError) {
                throw new SQLGenerationException("类未标记@Entity");
            }
            return null;
        }
        PsiAnnotation tableAnnotation = type.getAnnotation("com.sunnysuperman.repository.annotation.Table");
        if (tableAnnotation == null) {
            if (throwsOnError) {
                throw new SQLGenerationException("类未标记@Table");
            }
            return null;
        }
        // 表定义
        TableDefinition def = new TableDefinition();
        def.setName(AnnotationUtils.getStringValue(tableAnnotation, "name"));
        def.setComment(AnnotationUtils.getStringValue(tableAnnotation, "comment"));
        if (StringUtil.isEmpty(def.getComment())) {
            PsiAnnotation apiModelAnnotation = type.getAnnotation("io.swagger.annotations.ApiModel");
            if (apiModelAnnotation != null) {
                def.setComment(AnnotationUtils.getStringValue(apiModelAnnotation, "value"));
            }
        }
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
            if (StringUtil.isEmpty(column.getComment())) {
                PsiAnnotation apiModelPropsAnnotation = field.getAnnotation("io.swagger.annotations.ApiModelProperty");
                if (apiModelPropsAnnotation != null) {
                    column.setComment(AnnotationUtils.getStringValue(apiModelPropsAnnotation, "value"));
                }
            }
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
        if (fieldType instanceof PsiPrimitiveType) {
            return fieldType.getPresentableText();
        }
        PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
        if (fieldClass == null) {
            throw new SQLGenerationException("请确保类编译通过: " + fieldType.getCanonicalText());
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
            alert("已拷贝");
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

    private void alert(String msg) {
        JOptionPane.showMessageDialog(null, msg);
    }
}
